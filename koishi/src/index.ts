import { Context, Schema } from "koishi";
import type {} from "@koishijs/plugin-http";
import type {} from "@koishijs/plugin-server";
import { DbkGateway, watchBots } from "./dbk/gateway";
import { createGatewayHandlers } from "./dbk/handlers";

export const name = "dynamic-bot";

export const reusable = true;

export const inject = ["server", "http"];

export enum WebsocketType {
  SERVER = "server",
  CLIENT = "client",
}

export interface Config {
  websocketType: WebsocketType;
  accessToken: string;
  path?: string;
  host?: string;
  port?: number;
  reconnect?: boolean;
  dev: boolean;
}

export const Config: Schema<Config> = Schema.intersect([
  Schema.object({
    websocketType: Schema.union([
      Schema.const(WebsocketType.SERVER).description(
        "正向连接（dynamic-bot 连接本插件，复用 Koishi HTTP 服务）",
      ),
      Schema.const(WebsocketType.CLIENT).description(
        "反向连接（本插件连接 dynamic-bot）",
      ),
    ])
      .description("连接模式")
      .required(),
    accessToken: Schema.string()
      .role("secret")
      .required()
      .description("与 dynamic-bot 插件共享的访问令牌"),
    dev: Schema.boolean()
      .default(false)
      .description("使用 protojson 文本帧（仅本地调试）"),
  }).description("动态机器人连接配置"),
  Schema.union([
    Schema.object({
      websocketType: Schema.const(WebsocketType.SERVER).required(),
      path: Schema.string()
        .default("/dbk")
        .description(
          "挂载在 Koishi HTTP 服务上的 WebSocket 路径，无需单独开端口",
        ),
    }).description("正向连接配置"),
    Schema.object({
      websocketType: Schema.const(WebsocketType.CLIENT).required(),
      host: Schema.string()
        .default("127.0.0.1")
        .description("dynamic-bot 反向 WebSocket 监听地址"),
      port: Schema.number()
        .min(1)
        .max(65535)
        .default(9800)
        .description("dynamic-bot 反向 WebSocket 端口"),
      reconnect: Schema.boolean().default(true).description("断开后自动重连"),
    }).description("反向连接配置"),
  ]),
]);

export function apply(ctx: Context, config: Config) {
  const gateway = new DbkGateway(
    ctx,
    {
      encoding: config.dev ? "json" : "binary",
      accessToken: config.accessToken ?? "",
      path: config.path ?? "/dbk",
      host: config.host ?? "127.0.0.1",
      port: config.port ?? 9800,
      reconnect: config.reconnect ?? true,
    },
    createGatewayHandlers(ctx),
  );

  if (config.websocketType === WebsocketType.CLIENT) {
    gateway.startReverse();
  } else {
    gateway.startForward();
  }

  watchBots(ctx, gateway);
  ctx.on("dispose", () => gateway.stop());
}

export { DbkEvent, DbkMethod, PROTOCOL_VERSION } from "./dbk/protocol";
export type {
  DbkEncoding,
  DbkEventMap,
  DbkGatewayHandlers,
  DbkRpcMap,
} from "./dbk/protocol";
export { DbkRpcError } from "./dbk/error";
export { DbkGateway } from "./dbk/gateway";
