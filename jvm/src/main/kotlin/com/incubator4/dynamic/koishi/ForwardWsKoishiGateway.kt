package com.incubator4.dynamic.koishi

import java.net.URI
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<ForwardWsKoishiGateway>()

internal class ForwardWsKoishiGateway(
    config: KoishiConfig,
) : DbkKoishiGateway(config) {

    @Volatile
    private var client: DbkForwardClient? = null
    @Volatile
    private var reconnectAttempts: Int = 0
    @Volatile
    private var reconnectSuspendedReason: String? = null

    override fun startTransport() {
        openClient()
    }

    override fun stopTransport() {
        reconnectSuspendedReason = "stopped"
        val current = client
        client = null
        runCatching { current?.closeBlocking() }
    }

    private fun openClient() {
        if (closing) return
        val previous = client
        client = null
        runCatching { previous?.close() }
        val uri = runCatching { URI.create(config.url) }.getOrElse {
            logger.error(it) { "Koishi 正向连接地址无效：${config.url}" }
            throw it
        }
        val next = DbkForwardClient(uri)
        client = next
        next.connectionLostTimeout = 0
        next.connect()
    }

    private fun scheduleReconnect(reason: String) {
        if (closing || !config.reconnect || reconnectSuspendedReason != null) return
        val scope = scope ?: return
        val attempt = reconnectAttempts + 1
        reconnectAttempts = attempt
        val delayMs = reconnectDelayMillis(attempt)
        logger.warn { "Koishi 正向连接断开，将在 ${delayMs}ms 后重连：attempt=$attempt reason=$reason" }
        scope.launch {
            delay(delayMs)
            if (closing || reconnectSuspendedReason != null) return@launch
            openClient()
        }
    }

    private inner class DbkForwardClient(
        uri: URI,
    ) : WebSocketClient(uri) {
        private val dbkSession: DbkSession = createSession(
            sendBytes = { bytes ->
                if (!isOpen) error("WebSocket 未打开")
                send(bytes)
            },
            onDead = { reason ->
                runCatching { close(1000, reason) }
            },
        )

        override fun onOpen(handshakedata: ServerHandshake) {
            reconnectAttempts = 0
            val scope = scope ?: return
            scope.launch {
                runCatching {
                    attachSession(dbkSession)
                    handshake(dbkSession)
                }.onFailure { error ->
                    if (error is DbkRpcException && error.code == dbk.v1.ErrorCode.ERROR_CODE_UNAUTHORIZED) {
                        reconnectSuspendedReason = error.message
                        logger.warn { "Koishi 正向连接鉴权失败，已暂停自动重连：${error.message}" }
                    } else {
                        logger.warn(error) { "Koishi 正向握手失败" }
                    }
                    runCatching { close(1000, error.message) }
                }
            }
        }

        override fun onMessage(message: String) {
            dbkSession.onTextRejected()
        }

        override fun onMessage(bytes: ByteBuffer) {
            val data = ByteArray(bytes.remaining())
            bytes.get(data)
            dbkSession.onBinary(data)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            dbkSession.close("ws close code=$code")
            if (client !== this) return
            markAccountsUnavailable("WebSocket 已关闭：code=$code reason=$reason")
            if (!closing) {
                scheduleReconnect(reason.ifBlank { "code=$code remote=$remote" })
            }
        }

        override fun onError(ex: Exception) {
            logger.warn(ex) { "Koishi 正向 WebSocket 错误" }
        }
    }
}

private fun reconnectDelayMillis(completedAttempts: Int): Long {
    return RECONNECT_BACKOFF_MILLIS[completedAttempts.coerceIn(1, RECONNECT_BACKOFF_MILLIS.size) - 1]
}

private val RECONNECT_BACKOFF_MILLIS: LongArray = longArrayOf(
    5_000L,
    10_000L,
    30_000L,
    60_000L,
    5 * 60_000L,
    10 * 60_000L,
    30 * 60_000L,
    60 * 60_000L,
)
