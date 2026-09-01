import { create } from "@bufbuild/protobuf";
import type { Context } from "koishi";
import { SendStatus, TargetKind, type Target } from "../gen/dbk/v1/common_pb";
import {
  HelloResponseSchema,
  ListBotsResponseSchema,
  type SendParams,
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
    [DbkMethod.SESSION_HELLO]: (request) => {
      const bots = snapshot().bots;
      ctx.logger.debug(
        "session.hello: app=%s protocol=%s gateway=%s bots=%d keys=%s",
        request.appVersion || "-",
        request.protocolVersion || "-",
        GATEWAY_VERSION,
        bots.length,
        bots.map((bot) => bot.botKey).join(",") || "-",
      );
      return create(HelloResponseSchema, {
        protocolVersion: PROTOCOL_VERSION,
        gatewayVersion: GATEWAY_VERSION,
        bots,
      });
    },
    [DbkMethod.BOTS_LIST]: () => {
      const result = snapshot();
      ctx.logger.debug(
        "bots.list: count=%d keys=%s",
        result.bots.length,
        result.bots.map((bot) => bot.botKey).join(",") || "-",
      );
      return result;
    },
    [DbkMethod.TARGETS_LIST]: async (request) => {
      ctx.logger.debug(
        "targets.list: bot=%s kind=%s",
        request.botKey.trim() || "*",
        enumName(TargetKind, request.kind),
      );
      const result = await listTargets(ctx, request);
      ctx.logger.debug(
        "targets.list: count=%d incomplete=%s",
        result.targets.length,
        result.incomplete,
      );
      return result;
    },
    [DbkMethod.TARGETS_GET]: async (request) => {
      ctx.logger.debug(
        "targets.get: bot=%s target=%s",
        request.botKey.trim() || "*",
        formatTarget(request.target),
      );
      const result = await getTarget(ctx, request);
      ctx.logger.debug(
        "targets.get: unresolved=%s name=%s bots=%s",
        result.unresolved,
        result.target?.name || "-",
        result.target?.botKeys.join(",") || "-",
      );
      return result;
    },
    [DbkMethod.MESSAGE_SEND]: async (request) => {
      ctx.logger.debug(
        "message.send: bot=%s target=%s reply=%s units=%s",
        request.botKey.trim() || "-",
        formatTarget(request.target),
        request.replyToMessageId.trim() || "-",
        formatUnits(request),
      );
      const result = await sendMessage(ctx, request);
      ctx.logger.debug(
        "message.send: status=%s receipts=%d retryable=%s reason=%s",
        enumName(SendStatus, result.status),
        result.receipts.length,
        result.retryable,
        result.reason || "-",
      );
      return result;
    },
    [DbkMethod.MESSAGE_RECALL]: async (request) => {
      ctx.logger.debug(
        "message.recall: bot=%s target=%s message=%s",
        request.botKey.trim() || "-",
        formatTarget(request.target),
        request.messageId.trim() || "-",
      );
      const result = await recallMessage(ctx, request);
      ctx.logger.debug(
        "message.recall: status=%s retryable=%s reason=%s",
        enumName(SendStatus, result.status),
        result.retryable,
        result.reason || "-",
      );
      return result;
    },
  };
}

function formatTarget(target: Target | undefined): string {
  if (!target) return "-";
  const kind = enumName(TargetKind, target.kind);
  const id = target.id.trim() || "-";
  const guild = target.guildId.trim();
  return guild ? `${kind}:${id} guild=${guild}` : `${kind}:${id}`;
}

function formatUnits(request: SendParams): string {
  if (request.units.length === 0) return "-";
  return request.units.map((unit) => {
    if (unit.body.case === "normal") return `normal:${unit.body.value.segments.length}`;
    if (unit.body.case === "forward") return `forward:${unit.body.value.nodes.length}`;
    return "empty";
  }).join(",");
}

function enumName(map: Record<number, string>, value: number): string {
  return map[value] ?? String(value);
}
