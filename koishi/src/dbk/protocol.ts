import type { GenMessage } from "@bufbuild/protobuf/codegenv2";
import type { Message } from "@bufbuild/protobuf";
import { FrameSchema } from "../gen/dbk/v1/frame_pb";
import {
  BotChangedEventSchema,
  GetTargetRequestSchema,
  GetTargetResponseSchema,
  HelloRequestSchema,
  HelloResponseSchema,
  IncomingMessageSchema,
  ListBotsRequestSchema,
  ListBotsResponseSchema,
  ListTargetsRequestSchema,
  ListTargetsResponseSchema,
  RecallParamsSchema,
  RecallResultSchema,
  SendParamsSchema,
  SendResultSchema,
  type BotChangedEvent,
  type GetTargetRequest,
  type GetTargetResponse,
  type HelloRequest,
  type HelloResponse,
  type IncomingMessage,
  type ListBotsRequest,
  type ListBotsResponse,
  type ListTargetsRequest,
  type ListTargetsResponse,
  type RecallParams,
  type RecallResult,
  type SendParams,
  type SendResult,
} from "../gen/dbk/v1/rpc_pb";

export const PROTOCOL_VERSION = "1";
export const GATEWAY_VERSION = "0.1.0";

export const PING_INTERVAL_MS = 10_000;
export const PONG_TIMEOUT_MS = 20_000;

export const DbkMethod = {
  SESSION_HELLO: "session.hello",
  BOTS_LIST: "bots.list",
  TARGETS_LIST: "targets.list",
  TARGETS_GET: "targets.get",
  MESSAGE_SEND: "message.send",
  MESSAGE_RECALL: "message.recall",
} as const;

export const DbkEvent = {
  BOT_CHANGED: "bot.changed",
  MESSAGE_CREATED: "message.created",
} as const;

export type DbkMethodName = (typeof DbkMethod)[keyof typeof DbkMethod];
export type DbkEventName = (typeof DbkEvent)[keyof typeof DbkEvent];
export type DbkEncoding = "binary" | "json";

export interface DbkRpcMap {
  [DbkMethod.SESSION_HELLO]: { request: HelloRequest; response: HelloResponse };
  [DbkMethod.BOTS_LIST]: { request: ListBotsRequest; response: ListBotsResponse };
  [DbkMethod.TARGETS_LIST]: { request: ListTargetsRequest; response: ListTargetsResponse };
  [DbkMethod.TARGETS_GET]: { request: GetTargetRequest; response: GetTargetResponse };
  [DbkMethod.MESSAGE_SEND]: { request: SendParams; response: SendResult };
  [DbkMethod.MESSAGE_RECALL]: { request: RecallParams; response: RecallResult };
}

export interface DbkEventMap {
  [DbkEvent.BOT_CHANGED]: BotChangedEvent;
  [DbkEvent.MESSAGE_CREATED]: IncomingMessage;
}

export type DbkGatewayHandlers = {
  [K in keyof DbkRpcMap]: (request: DbkRpcMap[K]["request"]) => Promise<DbkRpcMap[K]["response"]> | DbkRpcMap[K]["response"];
};

interface RpcCodec<Req extends Message, Res extends Message> {
  request: GenMessage<Req>;
  response: GenMessage<Res>;
}

export const DbkRpcCodecs: { [K in keyof DbkRpcMap]: RpcCodec<DbkRpcMap[K]["request"], DbkRpcMap[K]["response"]> } = {
  [DbkMethod.SESSION_HELLO]: { request: HelloRequestSchema, response: HelloResponseSchema },
  [DbkMethod.BOTS_LIST]: { request: ListBotsRequestSchema, response: ListBotsResponseSchema },
  [DbkMethod.TARGETS_LIST]: { request: ListTargetsRequestSchema, response: ListTargetsResponseSchema },
  [DbkMethod.TARGETS_GET]: { request: GetTargetRequestSchema, response: GetTargetResponseSchema },
  [DbkMethod.MESSAGE_SEND]: { request: SendParamsSchema, response: SendResultSchema },
  [DbkMethod.MESSAGE_RECALL]: { request: RecallParamsSchema, response: RecallResultSchema },
};

export const DbkEventCodecs: { [K in keyof DbkEventMap]: GenMessage<DbkEventMap[K]> } = {
  [DbkEvent.BOT_CHANGED]: BotChangedEventSchema,
  [DbkEvent.MESSAGE_CREATED]: IncomingMessageSchema,
};

export { FrameSchema };
export type { Frame } from "../gen/dbk/v1/frame_pb";
export { FrameOp } from "../gen/dbk/v1/frame_pb";
export { ErrorCode, RpcErrorSchema } from "../gen/dbk/v1/common_pb";
export type { RpcError } from "../gen/dbk/v1/common_pb";
