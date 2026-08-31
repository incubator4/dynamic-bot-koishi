package top.colter.dynamic.koishi

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFieldVisibility
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigNumberKind

public data class KoishiConfig(
    val mode: KoishiConnectionMode = KoishiConnectionMode.FORWARD_WS,
    val url: String = "ws://127.0.0.1:9800",
    val host: String = "127.0.0.1",
    val port: Int = 9800,
    val accessToken: String = "",
    val reconnect: Boolean = true,
)

public enum class KoishiConnectionMode {
    FORWARD_WS,
    REVERSE_WS,
}

public object KoishiConfigForm {
    public val migrations: List<ConfigMigration> = listOf()

    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "Koishi 网关",
        description = "连接独立运行的 Koishi 进程；Bot 账号和投递目标由 Koishi 侧发现，不要在此填写平台 Token 或账号 ID。",
        fields = listOf(
            ConfigFieldSpec(
                path = "mode",
                label = "连接模式",
                type = ConfigFieldType.SELECT,
                section = "连接",
                description = "选择本插件和 Koishi 进程的连接方向。\n正向是本插件去连 Koishi；反向是 Koishi 来连本插件。",
                options = listOf(
                    top.colter.dynamic.core.config.ConfigFieldOption(
                        KoishiConnectionMode.FORWARD_WS.name,
                        "正向 WebSocket",
                    ),
                    top.colter.dynamic.core.config.ConfigFieldOption(
                        KoishiConnectionMode.REVERSE_WS.name,
                        "反向 WebSocket",
                    ),
                ),
                required = true,
                restartRequired = true,
                restartTarget = "Koishi 插件",
            ),
            ConfigFieldSpec(
                path = "url",
                label = "Koishi 连接地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                description = "正向模式下本插件主动连接的 Koishi DBK 地址。\n账号与频道由 Koishi 的 bots.list / targets.list 发现。",
                required = true,
                restartRequired = true,
                restartTarget = "Koishi 插件",
                visibleWhen = forwardWsOnly(),
            ),
            ConfigFieldSpec(
                path = "host",
                label = "反向 WebSocket 监听地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                description = "反向 WebSocket 服务监听的地址。\n本机使用通常填 127.0.0.1；如果填 0.0.0.0，请务必配置 Token。",
                restartRequired = true,
                restartTarget = "Koishi 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "port",
                label = "反向 WebSocket 端口",
                type = ConfigFieldType.NUMBER,
                section = "连接",
                description = "反向 WebSocket 服务监听的端口。\nKoishi 插件需要连接到这个端口。",
                min = 1,
                max = 65_535,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "Koishi 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "accessToken",
                label = "连接 Token",
                type = ConfigFieldType.SECRET,
                section = "连接",
                description = "校验对端身份的共享令牌。\n两端需要填写相同 Token；反向监听非本机地址时必须填写。",
                secret = true,
                restartRequired = true,
                restartTarget = "Koishi 插件",
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "连接",
                description = "正向连接断开后是否由插件自动重连。\n开启后会逐步拉长重连间隔；反向模式由 Koishi 进程自己重连。",
                restartRequired = true,
                restartTarget = "Koishi 插件",
                visibleWhen = forwardWsOnly(),
            ),
        ),
    )

    public fun validate(config: KoishiConfig) {
        require(config.port in 1..65_535) { "反向 WebSocket 端口必须在 1 到 65535 之间" }

        when (config.mode) {
            KoishiConnectionMode.FORWARD_WS -> {
                val url = config.url.trim()
                require(url.isNotBlank()) { "Koishi 连接地址不能为空" }
                require(url.startsWith("ws://") || url.startsWith("wss://")) {
                    "Koishi 连接地址必须是 ws:// 或 wss://"
                }
            }
            KoishiConnectionMode.REVERSE_WS -> {
                require(config.host.isNotBlank()) { "反向 WebSocket 监听地址不能为空" }
                require(config.accessToken.isNotBlank() || config.host.isLocalBindAddress()) {
                    "反向 WebSocket 监听非本地地址时必须配置 Token"
                }
            }
        }
    }

    private fun forwardWsOnly(): ConfigFieldVisibility = ConfigFieldVisibility(
        path = "mode",
        values = listOf(KoishiConnectionMode.FORWARD_WS.name),
    )

    private fun reverseWsOnly(): ConfigFieldVisibility = ConfigFieldVisibility(
        path = "mode",
        values = listOf(KoishiConnectionMode.REVERSE_WS.name),
    )
}

internal fun KoishiConfig.normalized(): KoishiConfig = copy(
    url = url.trim(),
    host = host.trim(),
    accessToken = accessToken.trim(),
)

internal fun KoishiConfig.endpointLabel(): String {
    return when (mode) {
        KoishiConnectionMode.FORWARD_WS -> url.trim().ifBlank { "未配置正向连接" }
        KoishiConnectionMode.REVERSE_WS -> "${host.trim()}:$port"
    }
}

private fun String.isLocalBindAddress(): Boolean {
    val value = trim().lowercase()
    return value == "localhost" ||
        value == "::1" ||
        value == "0:0:0:0:0:0:0:1" ||
        value.startsWith("127.")
}
