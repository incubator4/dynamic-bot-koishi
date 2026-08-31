import { create } from "@bufbuild/protobuf";
import type { Bot, Context } from "koishi";
import { ErrorCode, TargetKind, TargetSchema, type Target } from "../gen/dbk/v1/common_pb";
import {
  GetTargetResponseSchema,
  TargetInfoSchema,
  type GetTargetRequest,
  type GetTargetResponse,
  type TargetInfo,
} from "../gen/dbk/v1/rpc_pb";
import { pickAvatar } from "./avatar";
import { hasNestedChannels } from "./bots";
import { DbkRpcError } from "./error";

/** Satori `Universal.Channel.Type` (Koishi 4.18). TEXT=0 DIRECT=1 CATEGORY=2 VOICE=3. */
const CHANNEL_TEXT = 0;
const CHANNEL_DIRECT = 1;
const CHANNEL_CATEGORY = 2;
const CHANNEL_VOICE = 3;
/** Discord native thread types if an adapter leaks them instead of Satori TEXT. */
const DISCORD_THREAD_TYPES = new Set([10, 11, 12]);

interface ChannelLike {
  id?: string | number;
  type?: number;
  name?: string;
  parentId?: string;
  parent_id?: string;
  guildId?: string;
  avatar?: string;
}

interface GuildLike {
  id?: string | number;
  name?: string;
  title?: string;
  first_name?: string;
  type?: string;
  guildId?: string;
  guildName?: string;
  avatar?: string;
}

interface UserLike {
  id?: string | number;
  name?: string;
  nick?: string;
  username?: string;
  avatar?: string;
}

export async function getTarget(ctx: Context, request: GetTargetRequest): Promise<GetTargetResponse> {
  const botKey = request.botKey.trim();
  const target = request.target;

  if (botKey) {
    const bot = findBot(ctx, botKey);
    if (!bot) {
      throw new DbkRpcError(ErrorCode.NOT_FOUND, `bot not found: ${botKey}`);
    }
    const info = await resolveOnBot(bot, target);
    return info ? resolved(info) : unresolved(target);
  }

  // Empty bot_key: search non-hidden, non-qq bots in ctx.bots order; first successful resolve wins.
  for (const bot of listSearchableBots(ctx)) {
    const info = await resolveOnBot(bot, target);
    if (info) return resolved(info);
  }
  return unresolved(target);
}

function findBot(ctx: Context, botKey: string): Bot | undefined {
  return ctx.bots.find((bot) => !bot.hidden && botKeyOf(bot) === botKey);
}

function listSearchableBots(ctx: Context): Bot[] {
  return ctx.bots.filter((bot) => !bot.hidden && (bot.platform ?? "").toLowerCase() !== "qq");
}

function botKeyOf(bot: Bot): string {
  const platform = bot.platform ?? "";
  const selfId = bot.selfId ?? "";
  return platform && selfId ? `${platform}:${selfId}` : "";
}

async function resolveOnBot(bot: Bot, target: Target | undefined): Promise<TargetInfo | undefined> {
  const id = target?.id.trim() ?? "";
  if (!id) return undefined;
  const guildId = target?.guildId.trim() ?? "";
  const kind = target?.kind ?? TargetKind.UNSPECIFIED;

  try {
    switch (kind) {
      case TargetKind.USER:
        return await resolveUser(bot, id, guildId);
      case TargetKind.GROUP:
        return await resolveGroup(bot, id, guildId);
      case TargetKind.CHANNEL:
        return await resolveChannelKind(bot, id, guildId, TargetKind.CHANNEL);
      case TargetKind.THREAD:
        return await resolveChannelKind(bot, id, guildId, TargetKind.THREAD);
      default:
        return (
          (await resolveChannelKind(bot, id, guildId, TargetKind.UNSPECIFIED)) ??
          (await resolveGroup(bot, id, guildId)) ??
          (await resolveUser(bot, id, guildId))
        );
    }
  } catch {
    return undefined;
  }
}

async function resolveUser(bot: Bot, id: string, guildId: string): Promise<TargetInfo | undefined> {
  const user = await callBot(bot, "getUser", () => bot.getUser(id, guildId || undefined));
  if (user) return toUserInfo(bot, user, guildId);

  const friend = await callFriend(bot, id);
  if (friend) return toUserInfo(bot, friend, guildId);

  const channel = await callBot(bot, "getChannel", () => bot.getChannel(id, guildId || undefined));
  if (channel && channelKind(bot, channel, TargetKind.USER) === TargetKind.USER) {
    return toChannelInfo(bot, channel, TargetKind.USER, guildId);
  }
  return undefined;
}

async function resolveGroup(bot: Bot, id: string, guildId: string): Promise<TargetInfo | undefined> {
  if (!hasNestedChannels(bot)) {
    const guild = await callBot(bot, "getGuild", () => bot.getGuild(id));
    if (guild) {
      const kind = kindFromGuild(bot, guild);
      if (kind) return toGuildInfo(bot, guild, kind);
    }
  }
  return resolveChannelKind(bot, id, guildId, TargetKind.GROUP);
}

async function resolveChannelKind(
  bot: Bot,
  id: string,
  guildId: string,
  requested: TargetKind,
): Promise<TargetInfo | undefined> {
  const channel = await callBot(bot, "getChannel", () => bot.getChannel(id, guildId || undefined));
  if (!channel) return undefined;
  const kind = channelKind(bot, channel, requested);
  if (!kind) return undefined;
  return toChannelInfo(bot, channel, kind, guildId);
}

function channelKind(bot: Bot, channel: ChannelLike, requested: TargetKind): TargetKind | undefined {
  const nested = hasNestedChannels(bot);
  const type = channel.type ?? CHANNEL_TEXT;
  if (type === CHANNEL_DIRECT) return TargetKind.USER;
  if (type === CHANNEL_CATEGORY || type === CHANNEL_VOICE) return undefined;
  if (DISCORD_THREAD_TYPES.has(type) || requested === TargetKind.THREAD) {
    if (requested === TargetKind.USER) return undefined;
    if (requested === TargetKind.GROUP && nested) return undefined;
    return TargetKind.THREAD;
  }
  if (requested === TargetKind.USER) return undefined;
  if (!nested) {
    if (requested === TargetKind.CHANNEL) return TargetKind.CHANNEL;
    return TargetKind.GROUP;
  }
  if (requested === TargetKind.GROUP) return undefined;
  return TargetKind.CHANNEL;
}

function kindFromGuild(bot: Bot, guild: GuildLike): TargetKind | undefined {
  const chatType = guild.type;
  if (chatType === "private") return TargetKind.USER;
  if (chatType === "channel") return TargetKind.CHANNEL;
  if (chatType === "group" || chatType === "supergroup") return TargetKind.GROUP;
  if (hasNestedChannels(bot)) return undefined;
  return TargetKind.GROUP;
}

async function toChannelInfo(
  bot: Bot,
  channel: ChannelLike,
  kind: TargetKind,
  requestGuildId: string,
): Promise<TargetInfo> {
  const id = stringifyId(channel.id);
  const guildId = requestGuildId || stringifyId(channel.guildId);
  const guild = guildId ? await lookupGuild(bot, guildId) : undefined;
  return create(TargetInfoSchema, {
    target: create(TargetSchema, { kind, id, guildId }),
    name: channel.name?.trim() || id,
    guildId: stringifyId(guild?.id) || guildId,
    guildName: guildDisplayName(guild),
    avatar: pickAvatar(channel, guild),
    botKeys: [botKeyOf(bot)],
  });
}

function toGuildInfo(bot: Bot, guild: GuildLike, kind: TargetKind): TargetInfo {
  const id = stringifyId(guild.id) || stringifyId(guild.guildId);
  const guildId = kind === TargetKind.USER ? "" : id;
  return create(TargetInfoSchema, {
    target: create(TargetSchema, { kind, id, guildId }),
    name: guildDisplayName(guild) || id,
    guildId,
    guildName: kind === TargetKind.USER ? "" : guildDisplayName(guild),
    avatar: pickAvatar(guild),
    botKeys: [botKeyOf(bot)],
  });
}

function toUserInfo(bot: Bot, user: UserLike, guildId: string): TargetInfo {
  const id = stringifyId(user.id);
  return create(TargetInfoSchema, {
    target: create(TargetSchema, { kind: TargetKind.USER, id, guildId }),
    name: user.nick?.trim() || user.name?.trim() || user.username?.trim() || id,
    guildId,
    guildName: "",
    avatar: pickAvatar(user),
    botKeys: [botKeyOf(bot)],
  });
}

async function lookupGuild(bot: Bot, guildId: string): Promise<GuildLike | undefined> {
  return callBot(bot, "getGuild", () => bot.getGuild(guildId));
}

function guildDisplayName(guild: GuildLike | undefined): string {
  if (!guild) return "";
  return guild.name?.trim() || guild.guildName?.trim() || guild.title?.trim() || guild.first_name?.trim() || "";
}

function stringifyId(value: string | number | undefined): string {
  if (value === undefined || value === null) return "";
  return String(value).trim();
}

function resolved(target: TargetInfo): GetTargetResponse {
  return create(GetTargetResponseSchema, { target, unresolved: false });
}

function unresolved(requestTarget: Target | undefined): GetTargetResponse {
  return create(GetTargetResponseSchema, {
    unresolved: true,
    target: requestTarget
      ? create(TargetInfoSchema, {
          target: requestTarget,
        })
      : undefined,
  });
}

async function callBot<K extends string, T>(
  bot: Bot,
  method: K,
  fn: () => Promise<T>,
): Promise<T | undefined> {
  if (typeof (bot as unknown as Record<string, unknown>)[method] !== "function") return undefined;
  try {
    return await fn();
  } catch {
    return undefined;
  }
}

async function callFriend(bot: Bot, id: string): Promise<UserLike | undefined> {
  const getFriend = (bot as Bot & { getFriend?: (userId: string) => Promise<UserLike> }).getFriend;
  if (typeof getFriend !== "function") return undefined;
  try {
    return await getFriend.call(bot, id);
  } catch {
    return undefined;
  }
}
