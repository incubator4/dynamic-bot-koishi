# proto/

DBK 的唯一字段合同。改消息形状只改这里的 `.proto`。
生成分工：

- `buf lint` / `buf breaking`：合同门禁
- `buf generate`：**只**出 TypeScript（`koishi/src/gen`）
- Kotlin 由 `jvm` 的 Wire Gradle 插件在编译时从 **本目录** 生成，不要复制 `.proto` 到 `jvm/`
  不要手写平行 DTO。不要用 kotlinx.serialization protobuf。不要在 proto 里写 gRPC `service`。
  约定见 `docs/codegen.md`、`docs/protocol.md`。
