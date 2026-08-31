# Codegen

## 原则

**proto3 是 IDL，不是压缩格式。** 两边类型必须从同一份 `proto/` 生成。禁止：

- Kotlin / TS 各写一套 DTO
- `kotlinx.serialization` protobuf（与 Node proto3 不互通）
- 用官方 `protobuf-java` / `protobuf-kotlin` **再**加一层 Wire（JVM 只选 Wire）
- 把 `.proto` 复制进 `jvm/src/main/proto` 再维护第二份
  **不要写 `service` / RPC。** DBK 是自有 WebSocket，不是 gRPC。`.proto` 只定义 message 和 enum。

## 推荐：Buf 管合同，语言用各自的官方生成器

不要指望一条 `buf generate` 同时当 JVM 和 TS 的「最好生成器」。
| 职责 | 工具 | 原因 |
| --- | --- | --- |
| lint / 破坏性变更 | Buf | 合同门禁，和语言无关 |
| TypeScript | Buf + `protoc-gen-es` | protobuf-es 的正路 |
| Kotlin | **Wire Gradle 插件** | Wire 官方就是 Gradle，oneof / data class 比官方 Java proto 更适合本仓库 |

```
                 proto/dbk/v1/*.proto
                         │
            ┌────────────┴────────────┐
            │                         │
     buf lint / breaking        Wire Gradle
     buf generate (仅 TS)       compile 时生成 Kotlin
            │                         │
   koishi/src/gen/dbk/          jvm/build/generated/source/wire/
   （提交 git）                  （不提交，每次编译生成）
```

入口脚本：

```bash
./scripts/gen.sh
# 1. buf lint
# 2. buf generate          # 写出 proto/gen（TS）
# 3. ./gradlew -p jvm generateProtos   # 或 compileKotlin，触发 Wire
```

CI 至少：`buf lint`、`buf breaking --against` 上一 tag / main、`buf generate && git diff --exit-code -- koishi/src/gen`、`./gradlew -p jvm test`。

## 为什么不「Buf 一次生成两边」

- Wire 的一等公民是 Gradle `wire { sourcePath { srcDir("../proto") } }`，不是 Buf remote plugin。
- protobuf-es 的一等公民是 `buf.gen.yaml` + `protoc-gen-es`。
- 硬用 Buf 调官方 `protocolbuffers/kotlin` 会丢掉 Wire 的 Kotlin API，且和已拍板的 ADR-4 不一致。
  对齐靠 **同一目录的 `.proto` + 同一条 gen 脚本**，不靠同一个 protoc 插件。

## 文件放哪

```
proto/
  buf.yaml
  buf.gen.yaml          # 只配置 TS
  dbk/v1/*.proto
koishi/
  package.json          # 依赖 @bufbuild/protobuf、@bufbuild/protoc-gen-es
  src/gen/              # buf generate 输出，提交
jvm/
  build.gradle.kts      # com.squareup.wire，sourcePath = ../proto
  build/generated/...   # Wire 输出，gitignore
```

`buf.yaml` 的 module path 指向 `proto/` 自己（或仓库根 `modules: - path: proto`）。不要把 `jvm/`、`koishi/` 标成 Buf module。

## TypeScript（Buf）

`proto/buf.gen.yaml` 示意：

```yaml
version: v2
clean: true
inputs:
  - directory: .
plugins:
  - local: protoc-gen-es
    out: ../koishi/src/gen
    opt:
      - target=ts
```

在仓库根跑时用 `buf generate --template proto/buf.gen.yaml`，或 `cd proto && buf generate`（`local` 插件靠 `koishi/node_modules/.bin` 在 PATH 上）。

- `clean: true`：删掉的 message 不会留下烂文件。
- `target=ts`：直接生成 `.ts`，方便 Koishi ESM。
- 插件版本跟 `package.json` 里的 `@bufbuild/protoc-gen-es` 走，不要再用一套未锁定的 remote 插件（CI 离线会挂）。需要可复现再改 remote 并 **钉死版本**。
  生成物 **提交**。审 PR 能看见合同变成了什么类型；装 Koishi 插件的人不需要装 Buf。

## Kotlin（Wire）

`jvm/build.gradle.kts` 示意：

```kotlin
plugins {
    id("com.squareup.wire")
}
wire {
    sourcePath {
        srcDir(rootDir.resolve("../proto"))
        include("dbk/v1/**")
    }
    kotlin {
        rpcRole = "none"
        enumMode = "enum_class"
        boxOneOfsMinSize = 1
    }
}
dependencies {
    implementation("com.squareup.wire:wire-runtime:<align-with-plugin>")
}
```

要点：

- `sourcePath` 指向仓库 `proto/`，**不要复制 proto**。
- `rpcRole = none`：没有 gRPC service。
- `boxOneOfsMinSize = 1`：`Segment` 的 oneof 生成 `OneOf`，mapper 用 key 匹配。Wire 7 的 `oneofMode = sealed_class` 更干净，等能稳定解析后再切。
- `enumMode = enum_class`：和 TS 枚举一一对应。
- 输出放 `build/generated/source/wire`，**不提交**。Gradle 编译必跑生成，避免生成物和 proto 两份真相。
  Wire 插件版本与 `wire-runtime` 必须同一主版本。

## 编码

生成类同时具备 binary 与 protojson：

- 默认 WebSocket：**protobuf binary**
- 本机调试：protojson
  不要为两种编码写两套 message。

## proto 写法（为了两边生成都干净）

```protobuf
syntax = "proto3";
package dbk.v1;
```

- 目录与包一致：`proto/dbk/v1/*.proto`
- 枚举 `ENUM_UNSPECIFIED = 0`
- 互斥结构用 `oneof`，不要用字符串 `type` + 可选字段
- 字段只用新号；禁止复用号
- 文件选项（`java_package` 等）能不写就不写；Kotlin 包名跟 `package dbk.v1` 走即可。若要 `com.incubator4.dynamic...`，用 Buf managed mode 或 Wire 的包配置，不要在每个文件手写两套 package

## 演进

- `buf lint` 用 `STANDARD`
- `buf breaking` 用 `FILE`（第一版偏严；发布后不要擅自改松）
- 破坏性变更升 `dbk.v2` 或被 CI 拦住
- jar 与 npm **同一 git tag**
