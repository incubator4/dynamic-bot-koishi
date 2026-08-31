import { create } from "@bufbuild/protobuf";
import type { Bot, Context } from "koishi";
import { BotStatus, TargetKind } from "../gen/dbk/v1/common_pb";
import { BotSchema, type Bot as DbkBot } from "../gen/dbk/v1/rpc_pb";

/** Koishi/Satori `Status`: OFFLINE=0 ONLINE=1 CONNECT=2 DISCONNECT=3 RECONNECT=4 */
const KOISHI_STATUS_ONLINE = 1;
const KOISHI_STATUS_CONNECT = 2;
const KOISHI_STATUS_RECONNECT = 4;

const SATORI_MESSAGE_DELETE = "message.delete";
const SATORI_GUILD_LIST = "guild.list";
const SATORI_CHANNEL_LIST = "channel.list";
const SATORI_GUILD_GET = "guild.get";
const SATORI_CHANNEL_GET = "channel.get";
const SATORI_FRIEND_LIST = "friend.list";
const SATORI_USER_GET = "user.get";
const SATORI_USER_CHANNEL_CREATE = "user.channel.create";

export const BotFeature = {
  MESSAGE_RECALL: "message.recall",
  MESSAGE_FORWARD: "message.forward",
  MENTION_ALL: "mention.all",
  TARGETS_LIST: "targets.list",
  TARGET_USER: "target.user",
  TARGET_GROUP: "target.group",
  TARGET_CHANNEL: "target.channel",
  TARGET_THREAD: "target.thread",
} as const;

const TARGET_KIND_FEATURES: ReadonlyArray<readonly [TargetKind, string]> = [
  [TargetKind.USER, BotFeature.TARGET_USER],
  [TargetKind.GROUP, BotFeature.TARGET_GROUP],
  [TargetKind.CHANNEL, BotFeature.TARGET_CHANNEL],
  [TargetKind.THREAD, BotFeature.TARGET_THREAD],
];

const THREAD_METHODS = [
  "getThreadList",
  "listActiveThreads",
  "listPublicArchivedThreads",
  "listPrivateArchivedThreads",
  "startThread",
  "startThreadFromMessage",
  "startThreadWithoutMessage",
  "createThread",
] as const;

/** Native merged-forward APIs (OneBot / NapCat style). Discord thread-forwards do not count. */
const MERGED_FORWARD_METHODS = [
  "send_group_forward_msg",
  "send_private_forward_msg",
  "send_forward_msg",
  "upload_forward_msg",
  "sendGroupForwardMsg",
  "sendPrivateForwardMsg",
  "sendForwardMsg",
  "uploadForwardMsg",
  "sendGroupForwardMessage",
  "sendPrivateForwardMessage",
  "sendForwardMessage",
  "sendMergeForward",
  "send_merge_forward",
] as const;

export function listBots(ctx: Pick<Context, "bots">): DbkBot[] {
  return ctx.bots.filter((bot) => !bot.hidden).map(toDbkBot);
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
    features: botFeatures(bot),
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

export function botFeatures(bot: Bot): string[] {
  const features: string[] = [];
  if (canRecall(bot)) features.push(BotFeature.MESSAGE_RECALL);
  if (canMergedForward(bot)) features.push(BotFeature.MESSAGE_FORWARD);
  if (canMentionAll(bot)) features.push(BotFeature.MENTION_ALL);
  if (canListTargets(bot)) features.push(BotFeature.TARGETS_LIST);
  for (const kind of botTargetKinds(bot)) {
    const feature = targetKindFeature(kind);
    if (feature) features.push(feature);
  }
  return features;
}

export function botTargetKinds(bot: Bot): TargetKind[] {
  const kinds: TargetKind[] = [];
  const nested = hasNestedChannels(bot);
  const guilds = hasGuildSurface(bot);
  if (canDirectMessage(bot)) kinds.push(TargetKind.USER);
  if (nested) {
    kinds.push(TargetKind.CHANNEL);
    kinds.push(TargetKind.THREAD);
  } else if (guilds) {
    kinds.push(TargetKind.GROUP);
    kinds.push(TargetKind.CHANNEL);
  }
  if (hasThreadSurface(bot) && !kinds.includes(TargetKind.THREAD)) {
    kinds.push(TargetKind.THREAD);
  }
  return kinds;
}

export function targetKindFeature(kind: TargetKind): string | undefined {
  return TARGET_KIND_FEATURES.find(([value]) => value === kind)?.[1];
}

/** Nested `channel.list` under a guild: Discord/KOOK-style servers, not sendable GROUP. */
export function hasNestedChannels(bot: Bot): boolean {
  return hasBotMethod(bot, "getChannelList") || satoriFeatures(bot).includes(SATORI_CHANNEL_LIST);
}

/** Guild/chat list or get, including flat adapters where the guild is the sendable target. */
export function hasGuildSurface(bot: Bot): boolean {
  return (
    hasBotMethod(bot, "getGuildList")
    || hasBotMethod(bot, "getGuild")
    || satoriFeatures(bot).includes(SATORI_GUILD_LIST)
    || satoriFeatures(bot).includes(SATORI_GUILD_GET)
  );
}

export function canListDirectUsers(bot: Bot): boolean {
  return (
    hasBotMethod(bot, "getFriendList")
    || hasBotMethod(bot, "getUserList")
    || satoriFeatures(bot).includes(SATORI_FRIEND_LIST)
  );
}

function canDirectMessage(bot: Bot): boolean {
  return (
    canListDirectUsers(bot)
    || hasBotMethod(bot, "getUser")
    || hasBotMethod(bot, "createDirectChannel")
    || satoriFeatures(bot).includes(SATORI_USER_GET)
    || satoriFeatures(bot).includes(SATORI_USER_CHANNEL_CREATE)
    || looksDirectMessageOnly(bot)
  );
}

function hasThreadSurface(bot: Bot): boolean {
  if (THREAD_METHODS.some((name) => hasBotMethod(bot, name) || hasInternalMethod(bot, name))) {
    return true;
  }
  return satoriFeatures(bot).some((feature) => feature.includes("thread"));
}

function canRecall(bot: Bot): boolean {
  return hasBotMethod(bot, "deleteMessage") || satoriFeatures(bot).includes(SATORI_MESSAGE_DELETE);
}

function canListTargets(bot: Bot): boolean {
  return (
    hasBotMethod(bot, "getGuildList")
    || hasBotMethod(bot, "getChannelList")
    || hasBotMethod(bot, "getGuild")
    || hasBotMethod(bot, "getChannel")
    || satoriFeatures(bot).some((feature) => (
      feature === SATORI_GUILD_LIST
      || feature === SATORI_CHANNEL_LIST
      || feature === SATORI_GUILD_GET
      || feature === SATORI_CHANNEL_GET
    ))
  );
}

function canMentionAll(bot: Bot): boolean {
  if (encoderHandlesMentionAll(bot)) return true;
  if (hasGuildOrChannelSurface(bot)) return true;
  // Unsure and not a group-looking adapter: still declare unless it looks DM-only.
  return !looksDirectMessageOnly(bot);
}

function canMergedForward(bot: Bot): boolean {
  return MERGED_FORWARD_METHODS.some((name) => hasBotMethod(bot, name) || hasInternalMethod(bot, name));
}

function hasGuildOrChannelSurface(bot: Bot): boolean {
  return canListTargets(bot)
    || hasBotMethod(bot, "getGuildMember")
    || hasBotMethod(bot, "getGuildMemberList");
}

function looksDirectMessageOnly(bot: Bot): boolean {
  if (hasGuildOrChannelSurface(bot)) return false;
  const Encoder = messageEncoder(bot);
  if (Encoder && !encoderHandlesMentionAll(bot)) {
    // Encoder exists, cannot at-all, and there is no guild/channel API.
    return true;
  }
  return false;
}

function encoderHandlesMentionAll(bot: Bot): boolean {
  const visit = messageEncoder(bot)?.prototype?.visit;
  if (typeof visit !== "function") return false;
  try {
    const src = Function.prototype.toString.call(visit);
    return /type\s*===\s*['"]all['"]/.test(src)
      || /['"]all['"]\s*===\s*(?:attrs\.)?type/.test(src)
      || /id\s*===\s*['"]all['"]/.test(src)
      || /['"]all['"]\s*===\s*(?:attrs\.)?id/.test(src)
      || /@everyone/.test(src)
      || /atall/i.test(src);
  } catch {
    return false;
  }
}

function messageEncoder(bot: Bot): { prototype?: { visit?: unknown } } | undefined {
  return (bot.constructor as { MessageEncoder?: { prototype?: { visit?: unknown } } }).MessageEncoder;
}

function satoriFeatures(bot: Bot): string[] {
  const features = (bot as Bot & { features?: unknown }).features;
  if (!Array.isArray(features)) return [];
  return features.filter((item): item is string => typeof item === "string");
}

function hasInternalMethod(bot: Bot, name: string): boolean {
  return hasBotMethod((bot as Bot & { internal?: object }).internal, name);
}

function hasBotMethod(target: object | null | undefined, name: string): boolean {
  if (!target) return false;
  const value = (target as Record<string, unknown>)[name];
  return typeof value === "function" && !looksUnimplemented(value);
}

function looksUnimplemented(fn: Function): boolean {
  try {
    const src = Function.prototype.toString.call(fn);
    return /not implemented|NotImplementedError/i.test(src);
  } catch {
    return false;
  }
}
