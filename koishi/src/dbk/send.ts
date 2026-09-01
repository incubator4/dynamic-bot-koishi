import { create } from "@bufbuild/protobuf";
import { h, type Bot, type Context } from "koishi";
import type {} from "@koishijs/plugin-http";
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
const MEDIA_FALLBACK_TYPE = {
  image: "image/png",
  video: "video/mp4",
  audio: "audio/mpeg",
} as const;

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
      ctx.logger.debug("message.send: no sendable segments");
      failuresRetryable = false;
      failures.push("no sendable segments");
      return;
    }
    const outgoing = withQuote(elements, nextQuoteId());
    ctx.logger.debug(
      "message.send: bot.sendMessage channel=%s guild=%s elements=%d",
      channelId,
      guildId || "-",
      outgoing.length,
    );
    try {
      const ids = await bot.sendMessage(channelId, outgoing, guildId);
      if (aborted) return;
      quoteUsed = true;
      const kept = normalizeMessageIds(ids);
      if (kept.length === 0) {
        ctx.logger.debug("message.send: adapter returned no message id");
        unknownReasons.push("send returned no message id");
        return;
      }
      ctx.logger.debug("message.send: adapter ids=%s", kept.join(","));
      for (const messageId of kept) {
        receipts.push(create(SendReceiptSchema, { messageId, recallable }));
      }
    } catch (error) {
      if (aborted) return;
      if (isTimeoutError(error)) {
        quoteUsed = true;
        const reason = errorMessage(error) || "send timed out";
        ctx.logger.debug("message.send: adapter timeout: %s", reason);
        unknownReasons.push(reason);
        return;
      }
      const retryable = isRetryableSendError(error);
      ctx.logger.debug(
        "message.send: adapter error retryable=%s: %s",
        retryable,
        errorMessage(error) || "send failed",
      );
      failuresRetryable = failuresRetryable && retryable;
      failures.push(errorMessage(error) || "send failed");
    }
  };

  const sendRendered = async (render: Promise<El[]>): Promise<void> => {
    if (aborted) return;
    let elements: El[];
    try {
      elements = await render;
    } catch (error) {
      if (aborted) return;
      if (isTimeoutError(error)) {
        const reason = errorMessage(error) || "media fetch timed out";
        ctx.logger.debug("message.send: media timeout: %s", reason);
        unknownReasons.push(reason);
        return;
      }
      ctx.logger.debug("message.send: media error: %s", errorMessage(error) || "media fetch failed");
      failuresRetryable = failuresRetryable && isRetryableSendError(error);
      failures.push(errorMessage(error) || "media fetch failed");
      return;
    }
    await sendElements(elements);
  };

  const work = (async (): Promise<SendResult> => {
    for (const unit of request.units) {
      if (aborted) break;
      if (unit.body.case === "normal") {
        await sendRendered(segmentsToElements(ctx, unit.body.value.segments, { mentionAll }));
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
          await sendRendered(forwardNodeElements(ctx, node, { mentionAll }));
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

export async function segmentsToElements(
  ctx: Context,
  segments: Segment[],
  options?: SegmentRenderOptions,
): Promise<El[]> {
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
        if (uri) elements.push(await fetchMediaElement(ctx, "image", uri));
        break;
      }
      case "video": {
        const uri = segment.body.value.uri.trim();
        if (uri) elements.push(await fetchMediaElement(ctx, "video", uri));
        break;
      }
      case "audio": {
        const uri = segment.body.value.uri.trim();
        if (uri) elements.push(await fetchMediaElement(ctx, "audio", uri));
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

async function forwardNodeElements(
  ctx: Context,
  node: ForwardNode,
  options?: SegmentRenderOptions,
): Promise<El[]> {
  const body = await segmentsToElements(ctx, node.segments, options);
  const name = node.senderName.trim();
  if (!name) return body;
  return [h.text(`${name}\n`), ...body];
}

type MediaKind = keyof typeof MEDIA_FALLBACK_TYPE;

async function fetchMediaElement(ctx: Context, kind: MediaKind, uri: string): Promise<El> {
  ctx.logger.debug("message.send: fetch %s %s", kind, displayUri(uri));
  let buffer: Buffer;
  let mime: string;
  try {
    const inline = decodeInlineMedia(uri, kind);
    if (inline) {
      buffer = inline.buffer;
      mime = inline.mime;
    } else {
      const fetched = await fetchRemoteMedia(ctx, kind, uri);
      buffer = fetched.buffer;
      mime = fetched.mime;
    }
  } catch (error) {
    throw mediaFetchError(kind, uri, error);
  }
  if (buffer.length === 0) {
    throw mediaFetchError(kind, uri, new Error("empty body"));
  }
  ctx.logger.debug("message.send: fetched %s bytes=%d mime=%s", kind, buffer.length, mime);
  if (kind === "image") return h.image(buffer, mime);
  if (kind === "video") return h.video(buffer, mime);
  return h.audio(buffer, mime);
}

async function fetchRemoteMedia(
  ctx: Context,
  kind: MediaKind,
  uri: string,
): Promise<{ buffer: Buffer; mime: string }> {
  const http = ctx.http;
  if (typeof http?.file !== "function") {
    throw new Error("Koishi http service is required to send media");
  }
  const file = await http.file(uri);
  const buffer = mediaBytes(file.data);
  const mime = (file.mime ?? file.type ?? "").trim() || MEDIA_FALLBACK_TYPE[kind];
  return { buffer, mime };
}

function decodeInlineMedia(uri: string, kind: MediaKind): { buffer: Buffer; mime: string } | undefined {
  if (/^data:/i.test(uri)) {
    const parsed = decodeDataUri(uri);
    if (!parsed) throw new Error("invalid data URI");
    return { buffer: parsed.buffer, mime: parsed.mime || sniffMime(parsed.buffer, kind) };
  }
  const base64 = /^base64:\/\//i.exec(uri);
  if (base64) {
    const buffer = decodeBase64Payload(uri.slice(base64[0].length));
    return { buffer, mime: sniffMime(buffer, kind) };
  }
  return undefined;
}

function decodeDataUri(uri: string): { buffer: Buffer; mime: string } | undefined {
  const comma = uri.indexOf(",");
  if (comma < 5) return undefined;
  const parts = uri.slice(5, comma).split(";").map((part) => part.trim()).filter(Boolean);
  const isBase64 = parts.some((part) => part.toLowerCase() === "base64");
  const mime = parts.find((part) => part.includes("/")) ?? "";
  const payload = uri.slice(comma + 1);
  const buffer = isBase64
    ? decodeBase64Payload(payload)
    : Buffer.from(decodeURIComponent(payload.replace(/\+/g, " ")));
  return { buffer, mime };
}

function decodeBase64Payload(payload: string): Buffer {
  const cleaned = payload.replace(/\s/g, "").replace(/-/g, "+").replace(/_/g, "/");
  if (!cleaned) throw new Error("empty base64 payload");
  const buffer = Buffer.from(cleaned, "base64");
  if (buffer.length === 0) throw new Error("invalid base64 payload");
  return buffer;
}

function sniffMime(buffer: Buffer, kind: MediaKind): string {
  if (buffer.length >= 8 && buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) {
    return "image/png";
  }
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return "image/jpeg";
  }
  if (buffer.length >= 6 && buffer.toString("ascii", 0, 6).startsWith("GIF8")) {
    return "image/gif";
  }
  if (buffer.length >= 12 && buffer.toString("ascii", 0, 4) === "RIFF" && buffer.toString("ascii", 8, 12) === "WEBP") {
    return "image/webp";
  }
  return MEDIA_FALLBACK_TYPE[kind];
}

function mediaBytes(data: unknown): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (data instanceof ArrayBuffer) return Buffer.from(data);
  if (ArrayBuffer.isView(data)) {
    return Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  }
  throw new Error("media body is not binary");
}

function mediaFetchError(kind: MediaKind, uri: string, cause: unknown): Error {
  const error = new Error(`failed to fetch ${kind} ${displayUri(uri)}: ${errorMessage(cause) || "unknown error"}`);
  error.cause = cause;
  return error;
}

function displayUri(uri: string): string {
  if (/^data:/i.test(uri)) return "data:...";
  if (/^base64:\/\//i.test(uri)) return "base64://...";
  return uri.length > 200 ? `${uri.slice(0, 200)}...` : uri;
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
  for (const item of errorChain(error)) {
    const text = errorMessage(item);
    const lower = text.toLowerCase();
    const status = httpStatusOf(item);

    if (status === 401 || status === 403 || isForbidden(lower)) return false;
    if (status === 400 || status === 404) return false;
    if (status === 429 || (status !== undefined && status >= 500)) return true;
    if (isRetryableNetwork(item, lower)) return true;
    if (/\brate.?limit\b|too many requests/.test(lower)) return true;
  }
  return false;
}

function isTimeoutError(error: unknown): boolean {
  for (const item of errorChain(error)) {
    const text = errorMessage(item).toLowerCase();
    const status = httpStatusOf(item);
    if (item instanceof SendTimeoutError) return true;
    if (status === 408 || status === 504) return true;
    if (item && typeof item === "object") {
      const name = (item as { name?: unknown }).name;
      if (name === "TimeoutError" || name === "AbortError") return true;
      const code = (item as { code?: unknown }).code;
      if (code === "ETIMEDOUT" || code === "UND_ERR_CONNECT_TIMEOUT" || code === "ABORT_ERR") return true;
    }
    if (text.includes("timed out") || text.includes("timeout")) return true;
  }
  return false;
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

function errorChain(error: unknown): unknown[] {
  const seen = new Set<unknown>();
  const chain: unknown[] = [];
  let current: unknown = error;
  while (current != null && !seen.has(current) && chain.length < 4) {
    seen.add(current);
    chain.push(current);
    current = typeof current === "object" && "cause" in current
      ? (current as { cause?: unknown }).cause
      : undefined;
  }
  return chain;
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
