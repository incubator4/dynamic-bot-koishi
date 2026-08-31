import { create } from "@bufbuild/protobuf";
import type { Context } from "koishi";
import { BotChangeType } from "../gen/dbk/v1/common_pb";
import { BotChangedEventSchema } from "../gen/dbk/v1/rpc_pb";
import { toDbkBot } from "./bots";
import { DbkEvent, type DbkEncoding, type DbkEventMap, type DbkGatewayHandlers } from "./protocol";
import { DbkGatewaySession } from "./session";
import { attachSocket, sendSocket, type DbkSocket } from "./socket";

const RECONNECT_BACKOFF_MS = [
  5_000,
  10_000,
  30_000,
  60_000,
  5 * 60_000,
  10 * 60_000,
  30 * 60_000,
  60 * 60_000,
];

export interface DbkGatewayConfig {
  encoding: DbkEncoding;
  accessToken: string;
  path: string;
  host: string;
  port: number;
  reconnect: boolean;
}

export class DbkGateway {
  private session: DbkGatewaySession | undefined;
  private socket: DbkSocket | undefined;
  private closing = false;
  private reconnectAttempts = 0;
  private reconnectTimer: (() => void) | undefined;
  private reconnectSuspended = false;

  constructor(
    private readonly ctx: Context,
    private readonly config: DbkGatewayConfig,
    private readonly handlers: DbkGatewayHandlers,
  ) {}

  get isHandshook(): boolean {
    return this.session?.isHandshook ?? false;
  }

  emit<K extends keyof DbkEventMap>(method: K, event: DbkEventMap[K]): void {
    this.session?.emit(method, event);
  }

  startForward(): void {
    const path = normalizePath(this.config.path);
    this.ctx.logger.info("DBK forward WebSocket mounted on Koishi HTTP at %s", path);
    this.ctx.server.ws(path, (socket) => {
      this.attach("replaced", socket as DbkSocket);
    });
  }

  startReverse(): void {
    this.connectReverse();
  }

  stop(): void {
    this.closing = true;
    this.reconnectTimer?.();
    this.reconnectTimer = undefined;
    this.session?.close("plugin stop");
    this.session = undefined;
    this.socket?.close(1000, "plugin stop");
    this.socket = undefined;
  }

  private connectReverse(): void {
    if (this.closing) return;
    const url = `ws://${this.config.host}:${this.config.port}`;
    this.ctx.logger.info("DBK reverse WebSocket connecting to %s", url);
    const socket = this.ctx.http.ws(url) as DbkSocket;
    const onOpen = () => {
      this.reconnectAttempts = 0;
      this.attach("ws close", socket);
    };
    if (typeof socket.on === "function") {
      socket.on("open", onOpen);
      socket.on("error", (error) => this.ctx.logger.warn("DBK reverse WebSocket error: %s", error));
      socket.on("close", () => this.onReverseClosed());
    } else {
      socket.addEventListener?.("open", onOpen);
      socket.addEventListener?.("close", () => this.onReverseClosed());
    }
  }

  private attach(replaceReason: string, socket: DbkSocket): void {
    if (this.socket && this.socket !== socket) {
      this.ctx.logger.warn("replacing existing DBK connection");
      this.session?.close(replaceReason);
      this.socket.close(1000, replaceReason);
    }
    this.socket = socket;
    const session = new DbkGatewaySession({
      encoding: this.config.encoding,
      accessToken: this.config.accessToken,
      handlers: this.handlers,
      logger: this.ctx.logger,
      send: (data) => sendSocket(socket, data),
      onDead: (reason) => {
        socket.close(1000, reason.slice(0, 123));
      },
      onUnauthorized: () => {
        this.reconnectSuspended = true;
        this.ctx.logger.warn("DBK unauthorized, automatic reconnect paused");
      },
    });
    this.session = session;
    attachSocket(socket, session, this.config.encoding);
  }

  private onReverseClosed(): void {
    this.session?.close("ws close");
    if (this.socket) this.socket = undefined;
    this.session = undefined;
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.closing || !this.config.reconnect || this.reconnectSuspended) return;
    this.reconnectTimer?.();
    const attempt = this.reconnectAttempts + 1;
    this.reconnectAttempts = attempt;
    const delay = RECONNECT_BACKOFF_MS[Math.min(attempt, RECONNECT_BACKOFF_MS.length) - 1];
    this.ctx.logger.warn("DBK reverse disconnected, reconnecting in %dms (attempt=%d)", delay, attempt);
    this.reconnectTimer = this.ctx.setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connectReverse();
    }, delay);
  }
}

export function watchBots(ctx: Context, gateway: DbkGateway): void {
  const emit = (type: BotChangeType, bot: Parameters<typeof toDbkBot>[0]) => {
    if (bot.hidden && type !== BotChangeType.REMOVED) return;
    gateway.emit(DbkEvent.BOT_CHANGED, create(BotChangedEventSchema, {
      type,
      bot: toDbkBot(bot),
    }));
  };
  ctx.on("bot-added", (bot) => emit(BotChangeType.ADDED, bot));
  ctx.on("bot-removed", (bot) => emit(BotChangeType.REMOVED, bot));
  ctx.on("bot-status-updated", (bot) => emit(BotChangeType.UPDATED, bot));
}

function normalizePath(path: string): string {
  const trimmed = path.trim() || "/dbk";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}
