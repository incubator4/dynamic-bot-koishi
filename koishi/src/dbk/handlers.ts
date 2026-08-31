import { create } from "@bufbuild/protobuf";
import type { Context } from "koishi";
import {
  HelloResponseSchema,
  ListBotsResponseSchema,
} from "../gen/dbk/v1/rpc_pb";
import { listBots } from "./bots";
import { getTarget } from "./get-target";
import { listTargets } from "./list-targets";
import { DbkMethod, GATEWAY_VERSION, PROTOCOL_VERSION, type DbkGatewayHandlers } from "./protocol";
import { recallMessage } from "./recall";
import { sendMessage } from "./send";

export function createGatewayHandlers(ctx: Context): DbkGatewayHandlers {
  const snapshot = () => create(ListBotsResponseSchema, { bots: listBots(ctx) });

  return {
    [DbkMethod.SESSION_HELLO]: () => create(HelloResponseSchema, {
      protocolVersion: PROTOCOL_VERSION,
      gatewayVersion: GATEWAY_VERSION,
      bots: snapshot().bots,
    }),
    [DbkMethod.BOTS_LIST]: snapshot,
    [DbkMethod.TARGETS_LIST]: (request) => listTargets(ctx, request),
    [DbkMethod.TARGETS_GET]: (request) => getTarget(ctx, request),
    [DbkMethod.MESSAGE_SEND]: (request) => sendMessage(ctx, request),
    [DbkMethod.MESSAGE_RECALL]: (request) => recallMessage(ctx, request),
  };
}
