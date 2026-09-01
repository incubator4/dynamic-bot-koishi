# 决策记录

改这些结论时，同步改 `AGENTS.md` 和 `.cursor/rules/`。

## ADR-1 — 用 Koishi 做多平台出口，不写 Discord/Telegram/OneBot 原生插件

**状态：** 接受
要接两个以上平台时，重复实现各平台 API 不划算。Koishi 已有 Adapter 生态（含 `adapter-onebot`）。只接一个平台时，原生插件可以更干净；本仓库仍按多平台网关来做。

## ADR-2 — 自有协议 DBK，第一版不接 Satori

**状态：** 接受
JVM 实现的是 `MessageSinkPlugin`（发送四种结果、route、TargetKind、入站统一入口）。Satori 更贴近 Login/Channel。两边都由本仓库维护时，协议按 MessageSink 长，Koishi 只当执行器。

## ADR-3 — Koishi 侧是 Plugin 不是 Adapter

**状态：** 接受
Adapter 会把主从写反。Gateway 使用 `ctx.bots`、`bot.sendMessage`、`h()`、`session` 事件。

## ADR-4 — proto3 作为 IDL；编码格式其次

**状态：** 接受
目标是 **codegen 约束字段**，不是压体积。唯一合同是 `proto/`。JVM 用 Wire，Koishi 用 protobuf-es。禁止 kotlinx.serialization protobuf（与 Node proto3 不互通）。线上默认 protobuf 二进制，本机可用 protojson；换编码不得拆 schema。

## ADR-5 — proto + JVM + Koishi 放在同一 monorepo

**状态：** 接受
不并进 dynamic-bot 主仓库。本仓是一个插件产品、两份产物（fatJar + npm）。同一 git tag 发两端，避免 0.1 jar 配 0.2 npm。
不使用 Nx/Turbo；根目录脚本调用 `buf generate`、Gradle、pnpm 即可。

## ADR-6 — 不替代 dynamic-bot-onebot；OneBot 也可经 Koishi Adapter 接入

**状态：** 接受（修订）
本仓库不实现 OneBot 协议，也不替换 [dynamic-bot-onebot](https://github.com/Colter23/dynamic-bot-onebot)。NapCat / Lagrange 等仍可直接走原生 JVM OneBot 插件。

同一类 OneBot 后端也可以接到 Koishi 的 `adapter-onebot`，再经本桥（DBK）到达 dynamic-bot。此时本仓库只使用 `ctx.bots`（`bot.platform` 一般为 `onebot`），不直连 OneBot。

不要让两条路径绑定同一个账号。Koishi 官方 `adapter-qq`（`platform=qq`）仍排除，避免和原生 OneBot 插件的 QQ 路由抢同一 platform id。

## ADR-7 — 入站不做命令判定

**状态：** 接受
与 OneBot 插件相同：只映射为 `IncomingMessage`，由主程序按前缀、链接规则、插件过滤器分发。Koishi 不得 `session.send` 业务回复。

## ADR-8 — 发送超时是 UNKNOWN 不是 FAILED

**状态：** 接受
对齐 OneBot 的 `OneBotSendOutcome.Uncertain` 与主程序 `SEND_UNKNOWN`。未确认的成功不能当失败重试，否则可能双发。

## ADR-9 — 产物版本来自 git describe

**状态：** 接受
jar / handshake / `plugin.yml` 的产品版本用 `git describe --tags --always --abbrev=7 --dirty`，不手写 `0.1.0`。发布 tag 约定 `vX.Y.Z`；同一 tag 发 fatJar 和 npm。`protocol_version` 仍是协议合同，与 git 版本无关。
npm 的 `package.json` `version` 必须是合法 semver，不能直接塞 SHA：发布时写成 tag 去掉 `v`；开发、无 git 时回退 `0.0.0-dev`。Koishi 握手的 `gateway_version` 在本仓库检出里走 git describe，安装后的 npm 包走 `package.json`。JVM 可用 `DBK_VERSION` 覆盖（浅克隆或无 git 的 CI）。

## ADR-10 — Gateway 把媒体 URI 拉成字节再交给 adapter

**状态：** 接受
DBK 仍只传 URI（无 `media.upload`）。Discord / Telegram 无法访问 dynamic-bot 的本机或内网地址。Koishi 在 `message.send` 里把 image / video / audio 的 URI 解析成字节，再 `h.image` / `h.video` / `h.audio`(data, mime)，由 adapter 上传文件。`data:` 与 dynamic-bot 的 `base64://` 本地解码；http(s) 等其余 URI 用 `ctx.http.file`。不要把 URL 原样交给平台，也不要在解析失败后回退成外链。这不是本地文件探测：URI 已经在 segment 里。
