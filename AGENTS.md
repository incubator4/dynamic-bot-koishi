# Agent instructions

This repository is **one plugin product** with two runtimes: a Kotlin fatJar for [dynamic-bot](https://github.com/Colter23/dynamic-bot) and a Koishi plugin. They talk over a private protocol (**DBK**) whose **only contract** is `proto/`.

Read this file first. Longer rationale lives in `docs/`. Cursor also loads `.cursor/rules/*.mdc`.

## Layout

```
proto/     unique IDL (buf + proto3)
jvm/       dynamic-bot MessageSink plugin (fatJar)
koishi/    Koishi plugin (npm) — NOT a Koishi Adapter
docs/      design notes; do not fork rules from memory
```

## Non-negotiables

1. **dynamic-bot is the brain.** Subscriptions, drawing, commands, link parsing, and delivery state live there. Koishi is hands and feet only.
2. **Koishi-side code is a Plugin, not an Adapter.** An Adapter would invert control (Koishi treating dynamic-bot as a chat platform). Use `ctx.bots` / `session` / `h()`.
3. **Do not embed Node in the JVM or start Koishi from the fatJar.** Two processes, like NapCat + `dynamic-bot-onebot`.
4. **Do not replace QQ OneBot.** QQ stays `dynamic-bot-onebot`. This repo is for Discord / Telegram / KOOK / Feishu and other Koishi adapters.
5. `proto/` **is the only field contract.** After changing `.proto`, run codegen. Never hand-write a second `IncomingMessage` / `SendParams` in Kotlin or TypeScript.
6. **Do not use kotlinx.serialization protobuf** for the wire format. It is not interoperable with Node proto3. Use Wire (JVM) + protobuf-es (TS) from the same `.proto`.
7. **Koishi must not parse commands, draw cards, or** `session.send` **business replies.** Incoming events go to the JVM; replies go back through `message.send`.
8. **Send results must distinguish** `OK` **/** `PARTIAL` **/** `UNKNOWN` **/** `FAILED`**.** Timeouts are `UNKNOWN` (do not retry as failure).
9. **Same git tag versions the jar and the npm package.** Do not release them independently. Product version is `git describe --tags --always --abbrev=7 --dirty` (exact tag, or tag-N-sha, or short SHA if untagged). Handshake `app_version` / `gateway_version`, `plugin.yml`, and the fatJar name use that string. npm `package.json` version is the release tag without a leading `v` (must be semver); until the first tag it stays `0.0.0-dev` as a no-git fallback. Do not hardcode `0.1.0`.
10. **Encoding is secondary.** Prefer protobuf binary; protojson is allowed for local debug. Changing encoding must not fork the schema.

## When implementing

- Map JVM `Message` → generated `SendParams` only in `jvm/`.
- Map generated types → Koishi `h()` only in `koishi/`.
- Account discovery = `bots.list` (`ctx.bots`). Target discovery = `targets.list` (guild/channel APIs). Do not put bot tokens or account IDs in dynamic-bot config.
- Platform ids follow Koishi `bot.platform` (`discord`, `telegram`, …) → `PlatformId.of(platform)`. JVM `supportedTargetPlatforms` is that live set from `bots.list` / `bot.changed`; do not hardcode adapter names. Exclude `qq` (stays on `dynamic-bot-onebot`).
- Bot status follows Koishi `bot.status`: `ONLINE` → `READY`, `CONNECT`/`RECONNECT` → `CONNECTING`, `OFFLINE`/`DISCONNECT` → `UNAVAILABLE`. Do not use `bot.isActive`.
- `TargetKind` is the live union of modes each connected Koishi bot actually supports (`target.user` / `target.group` / `target.channel` / `target.thread` in `features`). Nested `channel.list` → `CHANNEL` (+ `THREAD`); flat guilds (no nested channels) → `GROUP` (and `CHANNEL` when the adapter uses that chat type); DMs → `USER`. Do not hardcode Discord=`CHANNEL` / Telegram=`GROUP`.
- Flat-guild `targets.list` may be empty with `incomplete=true` (typical Telegram). Hand-filled IDs must still send.

## Docs map

| Topic                           | File                   |
| ------------------------------- | ---------------------- |
| Chain, roles, what not to build | `docs/architecture.md` |
| Why proto-as-IDL and monorepo   | `docs/decisions.md`    |
| RPC, frames, identity, errors   | `docs/protocol.md`     |
| Buf / Wire / protobuf-es        | `docs/codegen.md`      |
