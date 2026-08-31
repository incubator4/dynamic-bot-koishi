# DBK 协议

App = JVM 插件（调用方）。Gateway = Koishi 插件（执行方）。
传输：WebSocket。正向（默认）App 连接 Gateway；反向 Gateway 连接 App。握手前丢弃其它帧。
字段以 `proto/dbk/v1` 为准。下文是语义说明；实现时以生成代码和 `.proto` 为准。

## 帧

`op`: `CALL` | `OK` | `ERROR` | `EVENT` | `PING` | `PONG`

- `CALL` / `OK` / `ERROR` 带 `id`（调用方生成）
- `EVENT` 带单调 `seq`（v1 不做断线补推，靠 JVM `dedupeKey`）
- 心跳约 10s；约 20s 无 PONG 则断开重连
- 鉴权只在 `session.hello`，不塞进每条 RPC
- 字段以 `proto/dbk/v1/frame.proto` 为准：`payload` 是对应 RPC/事件 message 的 protobuf 二进制；`method` 为点分名（`session.hello`、`message.created`）

## 身份

```
botKey  = "{platform}:{selfId}"     // discord:123
routeId = "koishi:{botKey}"         // 只存在 JVM，不进协议
```

`platform` 使用 Koishi `bot.platform` 原样，JVM `PlatformId.of(platform)`。
目标 `kind` 对齐 `TargetKind`：`USER` | `GROUP` | `CHANNEL` | `THREAD`。`id` 一律字符串。

## RPC（App → Gateway）

| method           | 对上 JVM                | 对上 Koishi                                |
| ---------------- | ----------------------- | ------------------------------------------ |
| `session.hello`  | 启动                    | 校验 token，返回 bot 快照                  |
| `bots.list`      | `listMessageSinkRoutes` | `ctx.bots`                                 |
| `targets.list`   | `listMessageTargets`    | guild/channel 列表                         |
| `targets.get`    | `resolveMessageTarget`  | 单条；没有则 `unresolved` 占位，不要 error |
| `message.send`   | `sendMessage`           | `bot.sendMessage` / 私聊 API               |
| `message.recall` | `recallMessage`         | `bot.deleteMessage`                        |

v1 不做 `media.upload`。图片 segment 只带 URI。

### bots.list

每条 bot：`botKey`、`platform`、`selfId`、`name`、`avatar`、`status`（`READY` | `CONNECTING` | `UNAVAILABLE`）、`features`。
`status` 对齐 Koishi `bot.status`：`ONLINE` → `READY`；`CONNECT` / `RECONNECT` → `CONNECTING`；`OFFLINE` / `DISCONNECT` → `UNAVAILABLE`。不要用 `bot.isActive`（它把正在连接也当成可用）。
`features` 示例：`message.recall`、`message.forward`、`mention.all`、`targets.list`。JVM 不按平台名猜能力。
只有 `READY` 可发送；`CONNECTING` 与 `UNAVAILABLE` 都不可发送。

### targets.list

可过滤 `botKey`、`kind`。同一目标被多 bot 看见时合并，`bot_keys` 列出。`incomplete=true` 表示列表不全（典型 Telegram）。
Discord 文本频道 → `CHANNEL`（带 `guild_id` / `guild_name`）。Telegram 群 → `GROUP`。私聊 → `USER`。不要把 CATEGORY/VOICE 当可发送目标。

### message.send

不要传 core 的整个 `Message`（deliveryPolicy 等留在 JVM）。只传 `bot_key`、`target`、`reply_to_message_id`、`units`。
`units` 对齐 OneBot 的 `OneBotSendUnit`：`normal`（segments）或 `forward`（nodes）。无 `message.forward` 时 Gateway 降级为多条 normal。
segment 用 proto `oneof`：text / image / video / audio / mention / mention_all / reply。
**结果必须是四种 `SendStatus`：**
| status | JVM |
| --- | --- |
| `OK` | `MessageSendResult.sent` |
| `PARTIAL` | `partiallySent` |
| `UNKNOWN` | `uncertain`（超时、无响应）**禁止当失败重试** |
| `FAILED` | `failed`，尊重 `retryable` |
业务失败用 `OK` 帧 + `SendResult.status=FAILED`。`op=ERROR` 只用于 RPC 没执行成（bot 不存在、未握手）。
发送超时建议 20–30s，由 Gateway 回 `UNKNOWN`，不要自己重发。

### message.recall

无 `message.recall` feature 时 `FAILED` + `retryable=false`，不要假装成功。

## 事件（Gateway → App）

### bot.changed

`added` | `updated` | `removed`，payload 为单条 bot。

### message.created

字段按 core `IncomingMessage` 语义：`bot_key`、`platform`、`target`、`sender_id`、`message_id`、`timestamp`、`text`（命令用纯文本，可空）、`reply_to_message_id`、`mentions`、`segments`、可选 raw。
JVM 生成：

- `dedupeKey` = `koishi-gateway:{botKey}:{kind}:{id}:{messageId}`
- `traceId` = sha256(dedupeKey)
- `sourceEventId` = `{kind}:{id}:{messageId}`
- `IncomingMessagePublishRequest.replyToMessageId` = 平台消息 ID
  Gateway **禁止**：判断是否命令、过滤除自身消息外的业务、`session.send` 回复。建议丢弃 bot 自己发出的消息以免回声。

## ERROR code

`UNAUTHORIZED` | `PROTOCOL` | `NOT_FOUND` | `UNSUPPORTED` | `INTERNAL`
`TIMEOUT` 不要走 ERROR：发送路径应变成 `SendStatus.UNKNOWN`。

## v1 明确不做

断线 `seq` 补推、媒体探测/本地文件/base64、按钮与反应、经本桥发 QQ、Koishi 命令系统。
