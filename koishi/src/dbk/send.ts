import { create } from "@bufbuild/protobuf";
import { h, type Bot, type Context } from "koishi";
import { BotStatus, ErrorCode, SendStatus } from "../gen/dbk/v1/common_pb";
import {
  SendReceiptSchema,
  SendResultSchema,
  type ForwardNode,
  type Segment,
  type SendParams,
  type SendReceipt,
  type SendResult,
} from "../gen/dbk/v1/rpc_pb";
import { botFeatures, toDbkBotStatus } from "./bots";
import { DbkRpcError } from "./error";

const SEND_TIMEOUT_MS = 25_000;
const MENTION_ALL_FALLBACK = "@全体成员";

type El = ReturnType<typeof h>;

export interface SegmentRenderOptions {
  /** Prepend `h.quote` unless a reply segment already uses this id. */
  quoteMessageId?: string;
  /**
   * `mention_all` mapping:
   * - true → Satori `<at type="all"/>` (`h("at", { type: "all" })`)
   * - false → degrade to text `@全体成员` (not PARTIAL)
   */
  mentionAll?: boolean;
}

export async function sendMessage(ctx: Context, request: SendParams): Promise<SendResult> {
  const bot = findBot(ctx, request.botKey);
  if (!bot) {
    throw new DbkRpcError(ErrorCode.NOT_FOUND, `bot not found: ${request.botKey.trim() || "(empty)"}`);
  }

  const status = toDbkBotStatus(bot.status);
  if (status === BotStatus.CONNECTING) {
    return failed("bot is connecting", true);
  }
  if (status !== BotStatus.READY) {
    return failed("bot is unavailable", false);
  }

  const channelId = request.target?.id.trim() ?? "";
  if (!channelId) {
    return failed("target id is empty", false);
  }
  if (request.units.length === 0) {
    return failed("units is empty", false);
  }

  const guildId = request.target?.guildId.trim() || undefined;
  const features = botFeatures(bot);
  const recallable = features.includes("message.recall") || typeof bot.deleteMessage === "function";
  const mentionAll = features.includes("mention.all");
  const receipts: SendReceipt[] = [];
  const failures: string[] = [];
  const unknownReasons: string[] = [];
  let failuresRetryable = true;
  let quoteUsed = false;
  let aborted = false;

  const nextQuoteId = (): string | undefined => {
    if (quoteUsed) return undefined;
    const id = request.replyToMessageId.trim();
    return id || undefined;
  };

  const sendElements = async (elements: El[]): Promise<void> => {
    if (aborted) return;
    if (elements.length === 0) {
      failuresRetryable = false;
      failures.push("no sendable segments");
      return;
    }
    const outgoing = withQuote(elements, nextQuoteId());
    try {
      const ids = await bot.sendMessage(channelId, outgoing, guildId);
      if (aborted) return;
      quoteUsed = true;
      const kept = normalizeMessageIds(ids);
      if (kept.length === 0) {
        unknownReasons.push("send returned no message id");
        return;
      }
      for (const messageId of kept) {
        receipts.push(create(SendReceiptSchema, { messageId, recallable }));
      }
    } catch (error) {
      if (aborted) return;
      if (isTimeoutError(error)) {
        quoteUsed = true;
        unknownReasons.push(errorMessage(error) || "send timed out");
        return;
      }
      const retryable = isRetryableSendError(error);
      failuresRetryable = failuresRetryable && retryable;
      failures.push(errorMessage(error) || "send failed");
    }
  };

  const work = (async (): Promise<SendResult> => {
    for (const unit of request.units) {
      if (aborted) break;
      if (unit.body.case === "normal") {
        await sendElements(segmentsToElements(unit.body.value.segments, { mentionAll }));
      } else if (unit.body.case === "forward") {
        // v1: never emit a single merged-forward receipt. Always one send per node.
        const nodes = unit.body.value.nodes;
        if (nodes.length === 0) {
          failuresRetryable = false;
          failures.push("forward has no nodes");
          continue;
        }
        for (const node of nodes) {
          if (aborted) break;
          await sendElements(forwardNodeElements(node, { mentionAll }));
        }
      } else {
        failuresRetryable = false;
        failures.push("empty send unit");
      }
    }
    return finalize(receipts, failures, unknownReasons, failuresRetryable);
  })();

  try {
    return await withTimeout(work, SEND_TIMEOUT_MS);
  } catch (error) {
    aborted = true;
    void work.catch(() => undefined);
    if (error instanceof SendTimeoutError || isTimeoutError(error)) {
      return create(SendResultSchema, {
        status: SendStatus.UNKNOWN,
        reason: error instanceof Error ? error.message : "send timed out",
        retryable: false,
        receipts,
      });
    }
    return create(SendResultSchema, {
      status: SendStatus.FAILED,
      reason: errorMessage(error) || "message.send failed",
      retryable: isRetryableSendError(error),
      receipts,
    });
  }
}

export function segmentsToElements(segments: Segment[], options?: SegmentRenderOptions): El[] {
  const elements: El[] = [];
  const quoted = new Set<string>();

  const addQuote = (id: string) => {
    const trimmed = id.trim();
    if (!trimmed || quoted.has(trimmed)) return;
    quoted.add(trimmed);
    elements.push(h.quote(trimmed));
  };

  if (options?.quoteMessageId) addQuote(options.quoteMessageId);

  for (const segment of segments) {
    switch (segment.body.case) {
      case "text": {
        elements.push(h.text(segment.body.value.text));
        break;
      }
      case "image": {
        const uri = segment.body.value.uri.trim();
        if (uri) elements.push(h.image(uri));
        break;
      }
      case "video": {
        const uri = segment.body.value.uri.trim();
        if (uri) elements.push(h.video(uri));
        break;
      }
      case "audio": {
        const uri = segment.body.value.uri.trim();
        if (uri) elements.push(h.audio(uri));
        break;
      }
      case "mention": {
        const id = segment.body.value.id.trim();
        if (id) elements.push(h.at(id));
        break;
      }
      case "mentionAll": {
        if (options?.mentionAll === false) {
          elements.push(h.text(MENTION_ALL_FALLBACK));
        } else {
          elements.push(h("at", { type: "all" }));
        }
        break;
      }
      case "reply": {
        addQuote(segment.body.value.messageId);
        break;
      }
      case "link": {
        const url = segment.body.value.url.trim();
        if (!url) break;
        const title = segment.body.value.title.trim();
        elements.push(h("a", { href: url }, title || url));
        break;
      }
      case "unknown":
      case undefined:
        break;
    }
  }

  return elements;
}

function forwardNodeElements(node: ForwardNode, options?: SegmentRenderOptions): El[] {
  const body = segmentsToElements(node.segments, options);
  const name = node.senderName.trim();
  if (!name) return body;
  return [h.text(`${name}\n`), ...body];
}

function withQuote(elements: El[], quoteId: string | undefined): El[] {
  if (!quoteId) return elements;
  if (hasQuote(elements, quoteId)) return elements;
  return [h.quote(quoteId), ...elements];
}

function hasQuote(elements: El[], messageId: string): boolean {
  return elements.some((el) => el.type === "quote" && String(el.attrs?.id ?? "") === messageId);
}

function normalizeMessageIds(raw: unknown): string[] {
  if (raw == null) return [];
  const list = Array.isArray(raw) ? raw : [raw];
  return list.map((id) => String(id).trim()).filter(Boolean);
}

function findBot(ctx: Context, botKey: string): Bot | undefined {
  const key = botKey.trim();
  if (!key) return undefined;
  return ctx.bots.find((bot) => !bot.hidden && botKeyOf(bot) === key);
}

function botKeyOf(bot: Bot): string {
  const platform = bot.platform ?? "";
  const selfId = bot.selfId ?? "";
  return platform && selfId ? `${platform}:${selfId}` : "";
}

function finalize(
  receipts: SendReceipt[],
  failures: string[],
  unknownReasons: string[],
  failuresRetryable: boolean,
): SendResult {
  if (unknownReasons.length > 0) {
    return create(SendResultSchema, {
      status: SendStatus.UNKNOWN,
      reason: unknownReasons[0] ?? "send outcome unknown",
      retryable: false,
      receipts,
    });
  }
  if (failures.length === 0) {
    return create(SendResultSchema, {
      status: SendStatus.OK,
      reason: "",
      retryable: false,
      receipts,
    });
  }
  if (receipts.length > 0) {
    return create(SendResultSchema, {
      status: SendStatus.PARTIAL,
      reason: failures.join("; "),
      retryable: false,
      receipts,
    });
  }
  return create(SendResultSchema, {
    status: SendStatus.FAILED,
    reason: failures.join("; "),
    retryable: failuresRetryable,
    receipts: [],
  });
}

function failed(reason: string, retryable: boolean): SendResult {
  return create(SendResultSchema, {
    status: SendStatus.FAILED,
    reason,
    retryable,
  });
}

function isRetryableSendError(error: unknown): boolean {
  const text = errorMessage(error);
  const lower = text.toLowerCase();
  const status = httpStatusOf(error);

  if (status === 401 || status === 403 || isForbidden(lower)) return false;
  if (status === 400 || status === 404) return false;
  if (status === 429 || (status !== undefined && status >= 500)) return true;
  if (isRetryableNetwork(error, lower)) return true;
  if (/\brate.?limit\b|too many requests/.test(lower)) return true;
  return false;
}

function isTimeoutError(error: unknown): boolean {
  const text = errorMessage(error).toLowerCase();
  const status = httpStatusOf(error);
  if (error instanceof SendTimeoutError) return true;
  if (status === 408 || status === 504) return true;
  if (error && typeof error === "object") {
    const name = (error as { name?: unknown }).name;
    if (name === "TimeoutError" || name === "AbortError") return true;
    const code = (error as { code?: unknown }).code;
    if (code === "ETIMEDOUT" || code === "UND_ERR_CONNECT_TIMEOUT" || code === "ABORT_ERR") return true;
  }
  return text.includes("timed out") || text.includes("timeout");
}

function httpStatusOf(error: unknown): number | undefined {
  if (!error || typeof error !== "object") return undefined;
  const record = error as Record<string, unknown>;
  if (typeof record.status === "number") return record.status;
  if (typeof record.statusCode === "number") return record.statusCode;
  const response = record.response;
  if (response && typeof response === "object") {
    const nested = (response as Record<string, unknown>).status;
    if (typeof nested === "number") return nested;
  }
  if (typeof record.code === "number" && record.code >= 400 && record.code < 600) return record.code;
  return undefined;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message.trim();
  if (typeof error === "string") return error.trim();
  return String(error);
}

function isForbidden(lower: string): boolean {
  return lower.includes("forbidden")
    || lower.includes("missing permission")
    || lower.includes("missing access")
    || lower.includes("not permitted")
    || lower.includes("unauthorized");
}

function isRetryableNetwork(error: unknown, lower: string): boolean {
  if (error && typeof error === "object") {
    const code = (error as { code?: unknown }).code;
    if (
      code === "ECONNRESET"
      || code === "ECONNREFUSED"
      || code === "ECONNABORTED"
      || code === "ENOTFOUND"
      || code === "EAI_AGAIN"
      || code === "EPIPE"
      || code === "UND_ERR_SOCKET"
      || code === "UND_ERR_CONNECT_TIMEOUT"
    ) {
      return true;
    }
  }
  return lower.includes("network")
    || lower.includes("fetch failed")
    || lower.includes("socket hang up")
    || lower.includes("econnreset")
    || lower.includes("econnrefused");
}

class SendTimeoutError extends Error {
  constructor() {
    super("send timed out");
    this.name = "SendTimeoutError";
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new SendTimeoutError()), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
