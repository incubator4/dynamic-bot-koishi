import { create } from "@bufbuild/protobuf";
import { ErrorCode, RpcErrorSchema } from "../gen/dbk/v1/common_pb";
import { FrameOp, type Frame } from "../gen/dbk/v1/frame_pb";
import { createFrame, decodePayload, encodeFrame, encodePayload } from "./codec";
import { DbkRpcError } from "./error";
import {
  DbkEventCodecs,
  DbkMethod,
  DbkRpcCodecs,
  PING_INTERVAL_MS,
  PONG_TIMEOUT_MS,
  PROTOCOL_VERSION,
  type DbkEncoding,
  type DbkEventMap,
  type DbkGatewayHandlers,
  type DbkRpcMap,
} from "./protocol";

export interface DbkLogger {
  debug(...args: unknown[]): void;
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
  error(...args: unknown[]): void;
}

export interface DbkSessionOptions {
  encoding: DbkEncoding;
  accessToken: string;
  handlers: DbkGatewayHandlers;
  logger: DbkLogger;
  send: (data: Uint8Array | string) => void;
  onDead: (reason: string) => void;
  onUnauthorized?: () => void;
  now?: () => number;
}

export class DbkGatewaySession {
  private closed = false;
  private handshook = false;
  private seq = 0n;
  private sendQueue = Promise.resolve();
  private lastInboundAt: number;
  private heartbeat: ReturnType<typeof setInterval> | undefined;
  private readonly now: () => number;

  constructor(private readonly options: DbkSessionOptions) {
    this.now = options.now ?? Date.now;
    this.lastInboundAt = this.now();
  }

  get isHandshook(): boolean {
    return this.handshook && !this.closed;
  }

  onFrame(frame: Frame): void {
    if (this.closed) return;
    this.lastInboundAt = this.now();
    void this.handle(frame);
  }

  rejectText(): void {
    this.fail("DBK only accepts protobuf binary frames");
  }

  rejectDecode(error: unknown): void {
    this.options.logger.warn("DBK frame decode failed: %s", error);
    this.fail("DBK frame could not be decoded");
  }

  emit<K extends keyof DbkEventMap>(method: K, event: DbkEventMap[K]): void {
    if (!this.isHandshook) return;
    this.seq += 1n;
    this.enqueueFrame(
      createFrame({
        op: FrameOp.EVENT,
        seq: this.seq,
        method,
        payload: encodePayload(DbkEventCodecs[method], event as never),
      }),
    );
  }

  close(reason = "closed"): void {
    if (this.closed) return;
    this.closed = true;
    this.handshook = false;
    if (this.heartbeat) {
      clearInterval(this.heartbeat);
      this.heartbeat = undefined;
    }
    this.options.logger.debug("DBK session closed: %s", reason);
  }

  private async handle(frame: Frame): Promise<void> {
    switch (frame.op) {
      case FrameOp.PING:
        if (!this.handshook) return;
        this.enqueueFrame(createFrame({ op: FrameOp.PONG, id: frame.id }));
        return;
      case FrameOp.PONG:
        return;
      case FrameOp.CALL:
        await this.handleCall(frame);
        return;
      case FrameOp.EVENT:
      case FrameOp.OK:
      case FrameOp.ERROR:
        this.options.logger.debug("gateway ignored peer %s: method=%s", FrameOp[frame.op], frame.method);
        return;
      default:
        this.options.logger.warn("ignored DBK frame with unspecified op");
    }
  }

  private async handleCall(frame: Frame): Promise<void> {
    if (!frame.id) {
      this.replyError("", ErrorCode.PROTOCOL, "CALL is missing id");
      return;
    }
    if (!this.handshook && frame.method !== DbkMethod.SESSION_HELLO) {
      this.options.logger.debug("drop CALL before handshake: method=%s", frame.method);
      this.replyError(frame.id, ErrorCode.PROTOCOL, "DBK handshake is not complete");
      return;
    }
    if (!(frame.method in DbkRpcCodecs)) {
      this.replyError(frame.id, ErrorCode.UNSUPPORTED, `unknown method: ${frame.method}`);
      return;
    }
    try {
      const payload = await this.dispatchCall(frame.method, frame.payload);
      this.enqueueFrame(
        createFrame({
          op: FrameOp.OK,
          id: frame.id,
          method: frame.method,
          payload,
        }),
      );
    } catch (error) {
      const rpcError = error instanceof DbkRpcError
        ? error
        : new DbkRpcError(ErrorCode.INTERNAL, error instanceof Error ? error.message : String(error));
      this.options.logger.warn("DBK RPC failed: method=%s code=%s %s", frame.method, ErrorCode[rpcError.code], rpcError.message);
      this.replyError(frame.id, rpcError.code, rpcError.message);
      if (frame.method === DbkMethod.SESSION_HELLO && rpcError.code === ErrorCode.UNAUTHORIZED) {
        this.options.onUnauthorized?.();
        await this.sendQueue;
        this.fail(rpcError.message);
      }
    }
  }

  private dispatchCall(method: string, payload: Uint8Array): Promise<Uint8Array> {
    switch (method) {
      case DbkMethod.SESSION_HELLO:
        return this.runRpc(method, payload, (request) => this.hello(request));
      case DbkMethod.BOTS_LIST:
      case DbkMethod.TARGETS_LIST:
      case DbkMethod.TARGETS_GET:
      case DbkMethod.MESSAGE_SEND:
      case DbkMethod.MESSAGE_RECALL:
        return this.runRpc(method, payload, this.options.handlers[method]);
      default:
        return Promise.reject(new DbkRpcError(ErrorCode.UNSUPPORTED, `unknown method: ${method}`));
    }
  }

  private async runRpc<K extends keyof DbkRpcMap>(
    method: K,
    payload: Uint8Array,
    handler: DbkGatewayHandlers[K],
  ): Promise<Uint8Array> {
    const codec = DbkRpcCodecs[method];
    const request = decodePayload(codec.request, payload);
    const response = await handler(request);
    return encodePayload(codec.response, response);
  }

  private async hello(request: DbkRpcMap[typeof DbkMethod.SESSION_HELLO]["request"]): Promise<DbkRpcMap[typeof DbkMethod.SESSION_HELLO]["response"]> {
    if (request.token !== this.options.accessToken) {
      throw new DbkRpcError(ErrorCode.UNAUTHORIZED, "invalid access token");
    }
    const remote = request.protocolVersion.trim();
    if (remote && remote !== PROTOCOL_VERSION) {
      throw new DbkRpcError(
        ErrorCode.PROTOCOL,
        `protocol version mismatch: local=${PROTOCOL_VERSION} remote=${remote}`,
      );
    }
    const response = await this.options.handlers[DbkMethod.SESSION_HELLO](request);
    this.handshook = true;
    this.startHeartbeat();
    this.options.logger.info(
      "DBK handshake complete: bots=%d app=%s",
      response.bots.length,
      request.appVersion || "-",
    );
    return response;
  }

  private replyError(id: string, code: ErrorCode, detail: string): void {
    this.enqueueFrame(
      createFrame({
        op: FrameOp.ERROR,
        id,
        error: create(RpcErrorSchema, { code, detail }),
      }),
    );
  }

  private startHeartbeat(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.lastInboundAt = this.now();
    this.heartbeat = setInterval(() => {
      if (this.closed) return;
      if (this.now() - this.lastInboundAt >= PONG_TIMEOUT_MS) {
        this.fail("DBK heartbeat timed out");
        return;
      }
      this.enqueueFrame(createFrame({ op: FrameOp.PING }));
    }, PING_INTERVAL_MS);
  }

  private enqueueFrame(frame: Frame): void {
    this.sendQueue = this.sendQueue.then(() => {
      if (this.closed) return;
      this.options.send(encodeFrame(frame, this.options.encoding));
    }).catch((error) => {
      this.options.logger.warn("DBK send failed: %s", error);
      this.fail("DBK send failed");
    });
  }

  private fail(reason: string): void {
    if (this.closed) return;
    this.options.onDead(reason);
    this.close(reason);
  }
}
