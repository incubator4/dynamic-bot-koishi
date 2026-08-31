# dynamic-bot-koishi

[dynamic-bot](https://github.com/Colter23/dynamic-bot) 的 **Koishi 消息出口**：把 Koishi 里已经连上的 Discord / Telegram / KOOK / 飞书等账号，接到 dynamic-bot 的投递与入站链路。

对标 [dynamic-bot-onebot](https://github.com/Colter23/dynamic-bot-onebot)，但对接的是 Koishi 官方 Adapter，而不是 NapCat / OneBot。QQ 继续走 OneBot，本仓库不承接。

一份 git tag 同时版本化两端产物：

| 产物 | 用途 |
| --- | --- |
| `dynamic-bot-koishi-<version>-all.jar` | 放进 dynamic-bot 的 `plugins/` |
| [`koishi-plugin-dynamic-bot`](https://www.npmjs.com/package/koishi-plugin-dynamic-bot) | 装进独立运行的 Koishi 实例 |

## 它做什么 / 不做什么

dynamic-bot 是大脑：订阅、绘图、命令、链接解析、投递状态都在那边。本仓库只做手脚：把消息发出去、把聊天事件送回去。

- **是** 一个 Koishi **Plugin**，使用已有的 `ctx.bots`。
- **不是** Koishi Adapter，也不会把 dynamic-bot 伪装成聊天平台。
- **不** 在 fatJar 里启动 Node / 内嵌 Koishi。必须两个进程。
- **不** 在 Koishi 侧解析命令、画卡片或 `session.send` 业务回复。
- Discord / Telegram 等平台 Token 只写在 Koishi 控制台，不要写进 dynamic-bot 配置。

## 运行时

```
Discord / Telegram / KOOK / 飞书 / …
        ↕  Koishi 官方 Adapter（用户自行安装）
Koishi 实例
        ↕  koishi/  （本仓库 Plugin）
        ↕  DBK（WebSocket；字段由 proto/ 定义）
jvm/  dynamic-bot 插件（MessageSink fatJar）
        ↕  dynamic-bot-core
dynamic-bot 主程序
```

两端用共享 Token 握手。默认正向：JVM 去连 Koishi；也可以反向：Koishi 去连 JVM。

## 快速开始

需要已经能跑的 [dynamic-bot](https://github.com/Colter23/dynamic-bot) 和 [Koishi](https://koishi.chat/) 4.18+（Node 20+）。Koishi 里先装好目标平台的官方 Adapter 并完成登录。

### 1. 安装 JVM 插件

从 [Releases](https://github.com/incubator4/dynamic-bot-koishi/releases) 下载 `dynamic-bot-koishi-vX.Y.Z-all.jar`，放到 dynamic-bot 的 `plugins/` 后重启。

从源码构建：

```bash
./jvm/gradlew -p jvm fatJar
# 产物：jvm/build/libs/dynamic-bot-koishi-<git-describe>-all.jar
```

### 2. 安装 Koishi 插件

在 Koishi 插件市场搜索 **dynamic-bot**，或：

```bash
npm i koishi-plugin-dynamic-bot
```

本仓库开发可直接 `pnpm install` 后 `pnpm koishi:dev`（根目录 `package.json` 已把 workspace 插件挂进本地 Koishi）。

### 3. 配同一套 Token，选一种连接方向

两边填 **相同的连接 Token**。平台 Bot Token 仍只在 Koishi Adapter 里。

**正向（默认，推荐本机）** — JVM 连接 Koishi 的 HTTP WebSocket：

| 侧 | 项 | 示例 |
| --- | --- | --- |
| Koishi | 连接模式 | 正向连接（`server`） |
| Koishi | 路径 | `/dbk`（默认，挂在 Koishi HTTP 上，不必另开端口） |
| Koishi | Token | 自定共享令牌 |
| dynamic-bot | 连接模式 | 正向 WebSocket |
| dynamic-bot | Koishi 连接地址 | `ws://127.0.0.1:5140/dbk`（端口用你的 Koishi HTTP 端口） |

**反向** — Koishi 连接 JVM 监听端口（默认 `127.0.0.1:9800`）：

| 侧 | 项 | 示例 |
| --- | --- | --- |
| dynamic-bot | 连接模式 | 反向 WebSocket |
| dynamic-bot | 监听地址 / 端口 | `127.0.0.1` / `9800`（非本机监听必须填 Token） |
| Koishi | 连接模式 | 反向连接（`client`） |
| Koishi | host / port | 与 JVM 监听一致 |

账号和频道由 Koishi 连接后自动发现（`bots.list` / `targets.list`），不要在 dynamic-bot 里填账号 ID。部分平台（典型 Telegram）目标列表可能不完整，手工填写的 ID 仍可发送。

## 仓库结构

```
proto/     DBK 字段合同（buf + proto3；改字段只改这里）
jvm/       dynamic-bot MessageSink 插件（Kotlin fatJar）
koishi/    Koishi Plugin（npm）
docs/      架构、协议、codegen、决策记录
```

改 `.proto` 后跑 codegen，不要在 Kotlin / TypeScript 里再手写一套 DTO：

```bash
./scripts/gen.sh          # buf lint + TS 生成 + Wire generateProtos
pnpm proto:generate       # 仅 TypeScript
./jvm/gradlew -p jvm test fatJar
```

## 版本

jar、握手里的 `app_version` / `gateway_version`、`plugin.yml` 都来自：

```bash
git describe --tags --always --abbrev=7 --dirty
```

发布约定 tag `vX.Y.Z`，同一 tag 发 fatJar 和 npm。npm 的 `package.json` version 是去掉 `v` 的 semver；未打 tag 时回退 `0.0.0-dev`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 链路、职责、和 OneBot / Satori 的差别 |
| [docs/protocol.md](docs/protocol.md) | DBK RPC、身份、错误、发送结果 |
| [docs/codegen.md](docs/codegen.md) | proto 作为 IDL、Buf、Wire、protobuf-es |
| [docs/decisions.md](docs/decisions.md) | 已拍板的决策 |

贡献前请读根目录 [AGENTS.md](AGENTS.md)。

## License

[Apache License 2.0](LICENSE)
