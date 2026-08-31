# 决策记录

改这些结论时，同步改 `AGENTS.md` 和 `.cursor/rules/`。

## ADR-1 — 用 Koishi 做多平台出口，不写 Discord/Telegram 原生插件

**状态：** 接受
要接两个以上非 QQ 平台时，重复实现各平台 API 不划算。Koishi 已有 Adapter 生态。只接一个平台时，原生插件可以更干净；本仓库仍按多平台网关来做。

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

## ADR-6 — 不替代 dynamic-bot-onebot

**状态：** 接受
QQ / NapCat 的重连、图片投递、撤回已由 OneBot 插件处理。本桥不承载 QQ。

## ADR-7 — 入站不做命令判定

**状态：** 接受
与 OneBot 插件相同：只映射为 `IncomingMessage`，由主程序按前缀、链接规则、插件过滤器分发。Koishi 不得 `session.send` 业务回复。

## ADR-8 — 发送超时是 UNKNOWN 不是 FAILED

**状态：** 接受
对齐 OneBot 的 `OneBotSendOutcome.Uncertain` 与主程序 `SEND_UNKNOWN`。未确认的成功不能当失败重试，否则可能双发。
