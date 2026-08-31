import { create } from "@bufbuild/protobuf";
import type { Bot, Context } from "koishi";
import { ErrorCode, SendStatus } from "../gen/dbk/v1/common_pb";
import { RecallResultSchema, type RecallParams, type RecallResult } from "../gen/dbk/v1/rpc_pb";
import { DbkRpcError } from "./error";

/** Koishi/Satori `Status`: OFFLINE=0 ONLINE=1 CONNECT=2 DISCONNECT=3 RECONNECT=4 */
const KOISHI_STATUS_ONLINE = 1;
const KOISHI_STATUS_CONNECT = 2;
const KOISHI_STATUS_RECONNECT = 4;

const RECALL_TIMEOUT_MS = 25_000;

export async function recallMessage(ctx: Context, params: RecallParams): Promise<RecallResult> {
  const bot = findBot(ctx, params.botKey);
  if (!bot) {
    throw new DbkRpcError(ErrorCode.NOT_FOUND, `bot not found: ${params.botKey.trim() || "(empty)"}`);
  }

  if (!botCanRecall(bot)) {
    return failed("bot has no message.recall capability", false);
  }

  const notReady = notReadyResult(bot);
  if (notReady) return notReady;

  const messageId = params.messageId.trim();
  if (!messageId) {
    return failed("message_id is empty", false);
  }

  const channelId = params.target?.id.trim() ?? "";
  if (!channelId) {
    return failed("target id is empty", false);
  }

  try {
    await withTimeout(Promise.resolve(bot.deleteMessage(channelId, messageId)), RECALL_TIMEOUT_MS);
    return create(RecallResultSchema, {
      status: SendStatus.OK,
      reason: "",
      retryable: false,
    });
  } catch (error) {
    return classifyRecallError(error);
  }
}

function findBot(ctx: Context, botKey: string): Bot | undefined {
  const key = botKey.trim();
  if (!key) return undefined;
  return ctx.bots.find((bot) => botKeyOf(bot) === key);
}

function botKeyOf(bot: Bot): string {
  const platform = bot.platform ?? "";
  const selfId = bot.selfId ?? "";
  return platform && selfId ? `${platform}:${selfId}` : "";
}

function botCanRecall(bot: Bot): boolean {
  if (typeof bot.deleteMessage !== "function") return false;
  try {
    const src = Function.prototype.toString.call(bot.deleteMessage);
    if (/not implemented|NotImplementedError/i.test(src)) return false;
  } catch {
    // toString can throw on native/bound functions; treat as implemented.
  }
  return true;
}

function notReadyResult(bot: Bot): RecallResult | undefined {
  switch (bot.status) {
    case KOISHI_STATUS_ONLINE:
      return undefined;
    case KOISHI_STATUS_CONNECT:
    case KOISHI_STATUS_RECONNECT:
      return failed("bot is connecting", true);
    default:
      return failed("bot is unavailable", false);
  }
}

function failed(reason: string, retryable: boolean): RecallResult {
  return create(RecallResultSchema, {
    status: SendStatus.FAILED,
    reason,
    retryable,
  });
}

function unknown(reason: string): RecallResult {
  return create(RecallResultSchema, {
    status: SendStatus.UNKNOWN,
    reason,
    retryable: false,
  });
}

function classifyRecallError(error: unknown): RecallResult {
  if (error instanceof RecallTimeoutError) {
    return unknown(error.message);
  }

  const text = errorMessage(error);
  const lower = text.toLowerCase();
  const status = httpStatusOf(error);

  if (isTimeout(error, lower, status)) {
    return unknown(text || "message.recall timed out");
  }

  if (isNotImplemented(lower)) {
    return failed("bot has no message.recall capability", false);
  }

  if (status === 401 || status === 403 || isForbidden(lower)) {
    return failed(text || "not permitted to recall message", false);
  }

  if (status === 400 || status === 404) {
    return failed(text || "recall rejected", false);
  }

  if (status === 429 || (status !== undefined && status >= 500) || isRetryableNetwork(error, lower)) {
    return failed(text || "recall failed", true);
  }

  return failed(text || "message.recall failed", false);
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

function isTimeout(error: unknown, lower: string, status: number | undefined): boolean {
  if (status === 408 || status === 504) return true;
  if (error && typeof error === "object") {
    const name = (error as { name?: unknown }).name;
    if (name === "TimeoutError" || name === "AbortError") return true;
    const code = (error as { code?: unknown }).code;
    if (code === "ETIMEDOUT" || code === "UND_ERR_CONNECT_TIMEOUT" || code === "ABORT_ERR") return true;
  }
  return lower.includes("timed out") || lower.includes("timeout");
}

function isNotImplemented(lower: string): boolean {
  return lower.includes("not implemented") || lower.includes("unsupported");
}

function isForbidden(lower: string): boolean {
  return lower.includes("forbidden")
    || lower.includes("not permitted")
    || lower.includes("missing permission")
    || lower.includes("missing access")
    || lower.includes("can't delete")
    || lower.includes("cannot delete")
    || lower.includes("message can't be deleted")
    || lower.includes("not enough rights");
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

class RecallTimeoutError extends Error {
  constructor() {
    super("message.recall timed out");
    this.name = "RecallTimeoutError";
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new RecallTimeoutError()), ms);
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
