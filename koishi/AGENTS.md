# koishi/

Koishi **Plugin**：执行 DBK，调用已有 `ctx.bots`。不要实现 `Adapter` / `Bot` 去反向连接 dynamic-bot。
只做生成类型 ↔ `h()` / `session`。不要解析命令、不要 `session.send` 业务回复、不要在这里保存平台 Token 或 OneBot 连接（仍归 Koishi Adapter 配置，含 `adapter-onebot`）。
`message.send` 的 image / video / audio：用 `ctx.http.file` 拉 URI，再 `h.image` / `h.video` / `h.audio`(data, mime)。不要把 URL 原样交给 adapter。
`bot.status`：`ONLINE` → `READY`，`CONNECT`/`RECONNECT` → `CONNECTING`，其余 → `UNAVAILABLE`。不要用 `isActive`。
`targets.list` 在无嵌套 `channel.list` 的 bot 上可以不完整（典型 Telegram），必须设置 incomplete。
