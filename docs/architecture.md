# 架构

## 产品定位

本仓库是 [dynamic-bot](https://github.com/Colter23/dynamic-bot) 的 **Koishi 消息出口**，对标 [dynamic-bot-onebot](https://github.com/Colter23/dynamic-bot-onebot)：对接 Koishi 里已经连上的账号（Discord / Telegram / KOOK / 飞书 / **OneBot** 等 Adapter），而不是在本仓库实现各平台协议。
主程序保持平台无关。本插件不检测 B 站动态、不绘图、不解析命令。NapCat / Lagrange 既可以继续走原生 `dynamic-bot-onebot`，也可以接到 Koishi 的 `adapter-onebot` 再经本桥投递。

## 运行时链路

```
Discord / Telegram / KOOK / 飞书 / OneBot（NapCat / Lagrange / …） / …
        ↕  Koishi Adapter（用户在 Koishi 里安装，含 adapter-onebot）
Koishi 实例
        ↕  koishi/  （本仓库的 Koishi Plugin，不是 Adapter）
        ↕  DBK（WebSocket；字段由 proto/ 定义）
jvm/  dynamic-bot 插件（MessageSink fatJar）
        ↕  dynamic-bot-core
dynamic-bot 主程序（订阅、绘图、命令、链接解析、投递状态机）
```

必须是 **两个进程**：dynamic-bot 与 Koishi。禁止在 fatJar 里启动 Node / 内嵌 Koishi。

## 主从

| 角色    | 进程              | 职责                                              |
| ------- | ----------------- | ------------------------------------------------- |
| Brain   | dynamic-bot       | 订阅、绘图、命令前缀、链接解析、投递/撤回/重试    |
| App     | `jvm/`            | 实现 `AccountRoutedMessageSinkPlugin`，编解码 DBK |
| Gateway | `koishi/`         | 执行 RPC，把 `session` 推成入站事件               |
| Hands   | Koishi Adapter    | 各平台协议（含 OneBot，本仓库不实现）             |

箭头是 dynamic-bot **调用** Koishi，不是 Koishi 把 dynamic-bot 当成聊天平台。

## 术语：Plugin vs Adapter

Koishi 的 **Adapter** 含义是：`Koishi 核心 → Adapter → 某个聊天平台`。
若在 Koishi 里 `extend Adapter` 去连 dynamic-bot，语义会变成 Koishi 当大脑。本仓库 Koishi 侧必须是普通 **Plugin**，去使用已有的 `ctx.bots`。
对外可以说「Koishi 适配层」，代码类型不能是 Adapter。

## 配置落在哪

- dynamic-bot 插件配置：Gateway 地址、端口、共享 Token、正向/反向。 **没有** Discord Bot Token、没有账号列表。
- Koishi 控制台：各平台 Adapter 的 Token / OneBot WS。账号在连接后由 `bots.list` 发现。
  这与原生 OneBot 插件一致：配置里只有到 Gateway 的 WS，账号来自对端发现。

## 和 OneBot / Satori 的关系

OneBot 有两条互斥路径（不要绑同一个账号）：

```
路径 A（原生，不经本仓）
  NapCat / Lagrange  ↔  dynamic-bot-onebot  ↔  dynamic-bot

路径 B（经 Koishi Adapter）
  NapCat / Lagrange  ↔  Koishi adapter-onebot  ↔  本仓库 koishi/  ↔  DBK  ↔  本仓库 jvm/  ↔  dynamic-bot
```

本仓库对路径 B 只看到 `ctx.bots`（`platform` 一般为 `onebot`），不实现 OneBot 协议。其它平台同样只走 Koishi Adapter。

两进程对照（说明「本仓不内嵌 Node」，不是「禁止 OneBot」）：

```
hands 进程     NapCat                      ↔  用户的 Koishi + Adapter（含 adapter-onebot）
JVM 插件       dynamic-bot-onebot          ↔  本仓库 jvm/
协议           OneBot                      ↔  DBK（自有协议，proto 约束）
Node           无（NapCat 已是独立进程）    ↔  本仓库 koishi/（不塞进 fatJar）
```

不使用 `@koishijs/plugin-server-satori` 作为第一版合同：Satori 的 Login/Channel 模型和 core 的 MessageSink（四种发送状态、TargetKind、多账号 route）不完全同构。若以后编码改成 protojson，那也是 DBK 的编码，不是实现 Satori。
Koishi 官方 `adapter-qq`（`platform=qq`）仍排除，避免和路径 A 的 QQ 路由抢同一 platform id。

## 账号与目标发现（对标 OneBot）

OneBot 实现位置备忘（上游插件）：

- 对外：`OneBotGatewayPlugin.listMessageSinkRoutes` / `listMessageTargets`
- 账号：正向/反向 Gateway 调 `get_login_info`
- 目标：`get_group_list` / `get_friend_list`
  本仓库对应：
- 账号：`bots.list` ← `ctx.bots`（`platform` + `selfId` + 昵称 + status）
- `status`：Koishi `ONLINE` → `READY`，`CONNECT`/`RECONNECT` → `CONNECTING`，其余 → `UNAVAILABLE`
- 目标：`targets.list` ← guild/channel 列表；无嵌套 `channel.list` 的 bot 可 `incomplete=true`（典型 Telegram）
- JVM `supportedTargetPlatforms`：上述 bot 的 `platform` 去重（含 `onebot`），排除官方 `adapter-qq` 的 `qq`。用户新装 Koishi Adapter 后会自动出现，不要在 JVM 写死平台名
- JVM `supportedTargetKinds`：上述 bot 的 `target.user` / `target.group` / `target.channel` / `target.thread` features 去重。按该 bot 实际 API 探测（嵌套 `channel.list` vs 扁平 `guild.list`），不要写死 Discord=`CHANNEL`、Telegram=`GROUP`
- JVM `routeId`：`koishi:{platform}:{selfId}`

## 第一版范围

打通：`session.hello` → `bots.list` → `message.send`（文本+图片 URI；Gateway 拉 URI 后上传）→ `message.created`。
随后：`targets.list` / `targets.get`、撤回、正向+反向 WS。
明确不做：合并转发完美还原、媒体本地文件探测、Koishi 侧命令/订阅插件、事件断线补推。
