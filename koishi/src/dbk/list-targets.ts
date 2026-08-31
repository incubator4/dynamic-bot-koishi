import { create } from "@bufbuild/protobuf";
import type { Bot, Context } from "koishi";
import { ErrorCode, TargetKind } from "../gen/dbk/v1/common_pb";
import {
  ListTargetsResponseSchema,
  type ListTargetsRequest,
  type ListTargetsResponse,
} from "../gen/dbk/v1/rpc_pb";
import { pickAvatar } from "./avatar";
import { DbkRpcError } from "./error";

/** TEXT is 0 in both Koishi and Satori. CATEGORY is 2 in both. DIRECT/VOICE swapped: old Koishi DIRECT=3 VOICE=1; current Satori DIRECT=1 VOICE=3. */
const CHANNEL_TEXT = 0;
const CHANNEL_CATEGORY = 2;
const CHANNEL_DIRECT_OR_VOICE = new Set([1, 3]);
/** Discord thread types if an adapter leaks native type numbers. */
const DISCORD_THREAD_TYPES = new Set([10, 11, 12]);

const MAX_LIST_PAGES = 64;
const MAX_LIST_ITEMS = 10_000;

interface GuildLike {
  id?: string;
  name?: string;
  avatar?: string;
}

interface ChannelLike {
  id?: string;
  name?: string;
  type?: number;
  parentId?: string;
  parent_id?: string;
  avatar?: string;
}

interface ListedTarget {
  kind: TargetKind;
  id: string;
  guildId: string;
  name: string;
  guildName: string;
  avatar: string;
  botKeys: string[];
}

interface PageResult<T> {
  items: T[];
  truncated: boolean;
  unsupported: boolean;
  failed: boolean;
}

export async function listTargets(ctx: Context, request: ListTargetsRequest): Promise<ListTargetsResponse> {
  const bots = resolveBots(ctx, request.botKey);
  const kindFilter = request.kind ?? TargetKind.UNSPECIFIED;
  const merged = new Map<string, ListedTarget>();
  let incomplete = false;

  for (const bot of bots) {
    if (isQq(bot)) continue;
    const listed = await listTargetsForBot(ctx, bot, kindFilter);
    incomplete = incomplete || listed.incomplete;
    for (const target of listed.targets) {
      mergeTarget(merged, target);
    }
  }

  return create(ListTargetsResponseSchema, {
    targets: [...merged.values()].map((target) => ({
      target: { kind: target.kind, id: target.id, guildId: target.guildId },
      name: target.name,
      guildId: target.guildId,
      guildName: target.guildName,
      avatar: target.avatar,
      botKeys: target.botKeys,
    })),
    incomplete,
  });
}

function resolveBots(ctx: Context, botKey: string | undefined): Bot[] {
  const key = botKey?.trim() ?? "";
  const visible = ctx.bots.filter((bot) => !bot.hidden);
  if (!key) return visible;
  const found = findBot(ctx, key);
  if (!found) {
    throw new DbkRpcError(ErrorCode.NOT_FOUND, `bot not found: ${key}`);
  }
  return [found];
}

function findBot(ctx: Context, botKey: string): Bot | undefined {
  return ctx.bots.find((bot) => !bot.hidden && botKeyOf(bot) === botKey);
}

function botKeyOf(bot: Bot): string {
  const platform = bot.platform ?? "";
  const selfId = bot.selfId ?? "";
  return platform && selfId ? `${platform}:${selfId}` : "";
}

function isQq(bot: Bot): boolean {
  return (bot.platform ?? "").toLowerCase() === "qq";
}

function isTelegramLike(bot: Bot): boolean {
  return (bot.platform ?? "").toLowerCase() === "telegram";
}

function wantsKind(filter: TargetKind, kind: TargetKind): boolean {
  return filter === TargetKind.UNSPECIFIED || filter === kind;
}

async function listTargetsForBot(
  ctx: Context,
  bot: Bot,
  kindFilter: TargetKind,
): Promise<{ targets: ListedTarget[]; incomplete: boolean }> {
  const botKey = botKeyOf(bot);
  const targets: ListedTarget[] = [];
  let incomplete = isTelegramLike(bot);
  const wantGuilds =
    wantsKind(kindFilter, TargetKind.GROUP) ||
    wantsKind(kindFilter, TargetKind.CHANNEL) ||
    wantsKind(kindFilter, TargetKind.THREAD);
  const wantUsers = wantsKind(kindFilter, TargetKind.USER);

  if (wantGuilds) {
    const guilds = await paginate<GuildLike>((next) => callBotList(bot, "getGuildList", next));
    if (guilds.unsupported || guilds.failed || guilds.truncated) incomplete = true;
    if (guilds.failed && !guilds.unsupported) {
      ctx.logger?.debug("targets.list: %s getGuildList failed", botKey);
    }

    const nested = isTelegramLike(bot) ? "flat" : "unknown";
    let channelMode: "unknown" | "nested" | "flat" = nested;

    for (const guild of guilds.items) {
      const guildId = String(guild.id ?? "").trim();
      if (!guildId) continue;
      const guildName = String(guild.name ?? "").trim();

      if (channelMode === "flat") {
        pushTarget(targets, kindFilter, {
          kind: TargetKind.GROUP,
          id: guildId,
          guildId: "",
          name: guildName || guildId,
          guildName: "",
          avatar: pickAvatar(guild),
          botKeys: [botKey],
        });
        continue;
      }

      const channels = await paginate<ChannelLike>((next) => callBotList(bot, "getChannelList", next, guildId));
      if (channels.truncated) incomplete = true;
      if (channels.unsupported) {
        channelMode = "flat";
        pushTarget(targets, kindFilter, {
          kind: TargetKind.GROUP,
          id: guildId,
          guildId: "",
          name: guildName || guildId,
          guildName: "",
          avatar: pickAvatar(guild),
          botKeys: [botKey],
        });
        continue;
      }
      if (channels.failed) {
        incomplete = true;
        ctx.logger?.debug("targets.list: %s getChannelList failed guild=%s", botKey, guildId);
        continue;
      }
      channelMode = "nested";
      const classified = classifyGuildChannels(channels.items, guildId, guildName, pickAvatar(guild), botKey);
      for (const target of classified) {
        pushTarget(targets, kindFilter, target);
      }
    }
  }

  if (wantUsers) {
    const friends = await listDirectUsers(bot);
    if (friends.truncated) incomplete = true;
    if (friends.failed && !friends.unsupported) {
      incomplete = true;
      ctx.logger?.debug("targets.list: %s friend/user list failed", botKey);
    }
    for (const user of friends.items) {
      pushTarget(targets, kindFilter, {
        kind: TargetKind.USER,
        id: user.id,
        guildId: "",
        name: user.name,
        guildName: "",
        avatar: user.avatar,
        botKeys: [botKey],
      });
    }
  }

  return { targets, incomplete };
}

function classifyGuildChannels(
  channels: ChannelLike[],
  guildId: string,
  guildName: string,
  guildAvatar: string,
  botKey: string,
): ListedTarget[] {
  const byId = new Map<string, ChannelLike>();
  for (const channel of channels) {
    const id = String(channel.id ?? "").trim();
    if (id) byId.set(id, channel);
  }
  const out: ListedTarget[] = [];
  for (const channel of channels) {
    const id = String(channel.id ?? "").trim();
    if (!id) continue;
    const kind = classifyChannel(channel, byId);
    if (kind == null) continue;
    const name = String(channel.name ?? "").trim() || id;
    out.push({
      kind,
      id,
      guildId,
      name,
      guildName,
      avatar: pickAvatar(channel) || guildAvatar,
      botKeys: [botKey],
    });
  }
  return out;
}

function classifyChannel(channel: ChannelLike, byId: Map<string, ChannelLike>): TargetKind | null {
  const type = channel.type;
  if (type === CHANNEL_CATEGORY || (type != null && CHANNEL_DIRECT_OR_VOICE.has(type))) {
    return null;
  }
  if (type != null && DISCORD_THREAD_TYPES.has(type)) {
    return TargetKind.THREAD;
  }
  if (type != null && type !== CHANNEL_TEXT) {
    return null;
  }
  const parentId = String(channel.parentId ?? channel.parent_id ?? "").trim();
  if (parentId) {
    const parent = byId.get(parentId);
    if (parent && isTextLikeChannel(parent)) {
      return TargetKind.THREAD;
    }
  }
  return TargetKind.CHANNEL;
}

function isTextLikeChannel(channel: ChannelLike): boolean {
  const type = channel.type;
  if (type === CHANNEL_CATEGORY || (type != null && CHANNEL_DIRECT_OR_VOICE.has(type))) {
    return false;
  }
  return type == null || type === CHANNEL_TEXT || DISCORD_THREAD_TYPES.has(type);
}

async function listDirectUsers(bot: Bot): Promise<PageResult<{ id: string; name: string; avatar: string }>> {
  const collected: { id: string; name: string; avatar: string }[] = [];
  let truncated = false;
  let sawSupported = false;
  let sawFailure = false;
  let sawSuccess = false;

  for (const method of ["getFriendList", "getUserList"] as const) {
    const page = await paginate<unknown>((next) => callBotList(bot, method, next));
    if (page.unsupported) continue;
    sawSupported = true;
    truncated = truncated || page.truncated;
    if (page.failed) sawFailure = true;
    else sawSuccess = true;
    for (const item of page.items) {
      const user = userFromListItem(item);
      if (user) collected.push(user);
    }
  }

  return {
    items: collected,
    truncated,
    unsupported: !sawSupported,
    failed: sawSupported && sawFailure && !sawSuccess,
  };
}

function userFromListItem(item: unknown): { id: string; name: string; avatar: string } | undefined {
  if (!item || typeof item !== "object") return undefined;
  const rec = item as Record<string, unknown>;
  const nested = rec.user && typeof rec.user === "object" ? (rec.user as Record<string, unknown>) : rec;
  const id = String(nested.id ?? rec.id ?? rec.userId ?? rec.user_id ?? "").trim();
  if (!id) return undefined;
  const name = String(nested.name ?? nested.nick ?? rec.nick ?? rec.name ?? "").trim();
  return { id, name: name || id, avatar: pickAvatar(nested, rec) };
}

function pushTarget(targets: ListedTarget[], kindFilter: TargetKind, target: ListedTarget): void {
  if (!wantsKind(kindFilter, target.kind)) return;
  if (!target.botKeys[0]) return;
  targets.push(target);
}

function mergeTarget(merged: Map<string, ListedTarget>, incoming: ListedTarget): void {
  const key = `${incoming.kind}\0${incoming.id}\0${incoming.guildId}`;
  const existing = merged.get(key);
  if (!existing) {
    merged.set(key, { ...incoming, botKeys: [...incoming.botKeys] });
    return;
  }
  existing.botKeys = unionKeys(existing.botKeys, incoming.botKeys);
  if (!existing.name || existing.name === existing.id) {
    existing.name = incoming.name || existing.name;
  }
  if (!existing.guildName) existing.guildName = incoming.guildName;
  if (!existing.avatar) existing.avatar = incoming.avatar;
}

function unionKeys(left: string[], right: string[]): string[] {
  const seen = new Set(left);
  const out = [...left];
  for (const key of right) {
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(key);
  }
  return out;
}

async function paginate<T>(
  fetch: (next?: string) => Promise<unknown>,
): Promise<PageResult<T>> {
  const items: T[] = [];
  const seenTokens = new Set<string>();
  let next: string | undefined;
  let pages = 0;

  while (true) {
    pages += 1;
    if (pages > MAX_LIST_PAGES || items.length >= MAX_LIST_ITEMS) {
      return { items, truncated: true, unsupported: false, failed: false };
    }
    let raw: unknown;
    try {
      raw = await fetch(next);
    } catch (error) {
      if (isUnsupported(error)) {
        if (items.length > 0) {
          return { items, truncated: true, unsupported: false, failed: false };
        }
        return { items, truncated: false, unsupported: true, failed: false };
      }
      return { items, truncated: items.length > 0, unsupported: false, failed: true };
    }
    if (raw == null) break;
    if (typeof (raw as { then?: unknown }).then === "function") {
      raw = await (raw as Promise<unknown>);
    }
    if (isAsyncIterableObject(raw) && !hasListData(raw)) {
      try {
        const collected = await collectFromIterable<T>(raw);
        items.push(...collected.items);
        return {
          items,
          truncated: collected.truncated,
          unsupported: false,
          failed: false,
        };
      } catch (error) {
        if (isUnsupported(error) && items.length === 0) {
          return { items, truncated: false, unsupported: true, failed: false };
        }
        return { items, truncated: items.length > 0, unsupported: false, failed: true };
      }
    }
    const page = normalizePage<T>(raw);
    items.push(...page.data);
    const token = page.next;
    if (!token || seenTokens.has(token) || page.data.length === 0) break;
    seenTokens.add(token);
    next = token;
  }

  return { items, truncated: false, unsupported: false, failed: false };
}

function normalizePage<T>(raw: unknown): { data: T[]; next?: string } {
  if (Array.isArray(raw)) {
    return { data: raw as T[] };
  }
  if (!raw || typeof raw !== "object") {
    return { data: [] };
  }
  const rec = raw as { data?: unknown; next?: unknown };
  const data = Array.isArray(rec.data) ? (rec.data as T[]) : [];
  const next = rec.next == null || rec.next === "" ? undefined : String(rec.next);
  return { data, next };
}

function callBotList(bot: Bot, method: string, next?: string, guildId?: string): Promise<unknown> {
  const fn = (bot as unknown as Record<string, unknown>)[method];
  if (typeof fn !== "function") {
    return Promise.reject(new Error(`${method} is not supported`));
  }
  if (method === "getChannelList") {
    return Promise.resolve((fn as (guildId: string, next?: string) => unknown).call(bot, guildId ?? "", next));
  }
  return Promise.resolve((fn as (next?: string) => unknown).call(bot, next));
}

function hasListData(value: object): boolean {
  return Array.isArray((value as { data?: unknown }).data);
}

function isAsyncIterableObject(value: unknown): value is AsyncIterable<unknown> {
  return typeof value === "object" && value != null && Symbol.asyncIterator in value && !Array.isArray(value);
}

async function collectFromIterable<T>(iterable: AsyncIterable<unknown>): Promise<{ items: T[]; truncated: boolean }> {
  const items: T[] = [];
  for await (const item of iterable) {
    items.push(item as T);
    if (items.length >= MAX_LIST_ITEMS) return { items, truncated: true };
  }
  return { items, truncated: false };
}

function isUnsupported(error: unknown): boolean {
  if (error && typeof error === "object" && "name" in error) {
    const name = String((error as { name?: string }).name);
    if (/unsupported/i.test(name)) return true;
  }
  const message = error instanceof Error ? error.message : String(error);
  return /not (implemented|supported)|unsupported|is not a function/i.test(message);
}
