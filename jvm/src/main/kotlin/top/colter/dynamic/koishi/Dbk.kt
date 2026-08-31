package top.colter.dynamic.koishi

internal const val DBK_PROTOCOL_VERSION: String = "1"
internal const val DBK_APP_VERSION: String = "0.1.0"

internal const val DBK_PING_INTERVAL_MS: Long = 10_000L
internal const val DBK_PONG_TIMEOUT_MS: Long = 20_000L
internal const val DBK_HELLO_TIMEOUT_MS: Long = 15_000L
internal const val DBK_RPC_TIMEOUT_MS: Long = 15_000L
internal const val DBK_SEND_TIMEOUT_MS: Long = 30_000L

internal object DbkMethods {
    const val SESSION_HELLO: String = "session.hello"
    const val BOTS_LIST: String = "bots.list"
    const val TARGETS_LIST: String = "targets.list"
    const val TARGETS_GET: String = "targets.get"
    const val MESSAGE_SEND: String = "message.send"
    const val MESSAGE_RECALL: String = "message.recall"
    const val EVENT_BOT_CHANGED: String = "bot.changed"
    const val EVENT_MESSAGE_CREATED: String = "message.created"
}

internal class DbkRpcException(
    val code: dbk.v1.ErrorCode,
    override val message: String,
) : RuntimeException(message)
