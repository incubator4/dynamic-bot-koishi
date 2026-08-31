import { create } from "@bufbuild/protobuf";
import type { Bot, Context } from "koishi";
import { BotStatus, SendStatus } from "../gen/dbk/v1/common_pb";
import {
  BotSchema,
  GetTargetResponseSchema,
  HelloResponseSchema,
  ListBotsResponseSchema,
  ListTargetsResponseSchema,
  RecallResultSchema,
  SendResultSchema,
  type Bot as DbkBot,
} from "../gen/dbk/v1/rpc_pb";
import { DbkMethod, GATEWAY_VERSION, PROTOCOL_VERSION, type DbkGatewayHandlers } from "./protocol";

/** Koishi/Satori `Status`: OFFLINE=0 ONLINE=1 CONNECT=2 DISCONNECT=3 RECONNECT=4 */
const KOISHI_STATUS_ONLINE = 1;
const KOISHI_STATUS_CONNECT = 2;
const KOISHI_STATUS_RECONNECT = 4;

export function createGatewayHandlers(ctx: Context): DbkGatewayHandlers {
  const snapshot = () => create(ListBotsResponseSchema, { bots: ctx.bots.filter((bot) => !bot.hidden).map(toDbkBot) });

  return {
    [DbkMethod.SESSION_HELLO]: () => create(HelloResponseSchema, {
      protocolVersion: PROTOCOL_VERSION,
      gatewayVersion: GATEWAY_VERSION,
      bots: snapshot().bots,
    }),
    [DbkMethod.BOTS_LIST]: snapshot,
    [DbkMethod.TARGETS_LIST]: () => create(ListTargetsResponseSchema, {
      targets: [],
      incomplete: true,
    }),
    [DbkMethod.TARGETS_GET]: () => create(GetTargetResponseSchema, { unresolved: true }),
    [DbkMethod.MESSAGE_SEND]: () => create(SendResultSchema, {
      status: SendStatus.FAILED,
      reason: "message.send is not implemented",
      retryable: false,
    }),
    [DbkMethod.MESSAGE_RECALL]: () => create(RecallResultSchema, {
      status: SendStatus.FAILED,
      reason: "message.recall is not implemented",
      retryable: false,
    }),
  };
}

export function toDbkBot(bot: Bot): DbkBot {
  const platform = bot.platform ?? "";
  const selfId = bot.selfId ?? "";
  return create(BotSchema, {
    botKey: platform && selfId ? `${platform}:${selfId}` : "",
    platform,
    selfId,
    name: bot.user?.name || bot.user?.nick || "",
    avatar: bot.user?.avatar || "",
    status: toDbkBotStatus(bot.status),
    features: [],
  });
}

export function toDbkBotStatus(status: number): BotStatus {
  switch (status) {
    case KOISHI_STATUS_ONLINE:
      return BotStatus.READY;
    case KOISHI_STATUS_CONNECT:
    case KOISHI_STATUS_RECONNECT:
      return BotStatus.CONNECTING;
    default:
      return BotStatus.UNAVAILABLE;
  }
}
