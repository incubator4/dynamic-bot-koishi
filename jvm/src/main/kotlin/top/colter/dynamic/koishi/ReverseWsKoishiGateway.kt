package top.colter.dynamic.koishi

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<ReverseWsKoishiGateway>()

internal class ReverseWsKoishiGateway(
    config: KoishiConfig,
) : DbkKoishiGateway(config) {

    private val active = AtomicReference<WebSocket?>(null)
    @Volatile
    private var server: DbkReverseServer? = null

    override fun startTransport() {
        val next = DbkReverseServer(InetSocketAddress(config.host, config.port))
        next.isReuseAddr = true
        next.connectionLostTimeout = 0
        server = next
        next.start()
        logger.info { "Koishi 反向 WebSocket 已监听 ${config.host}:${config.port}" }
    }

    override fun stopTransport() {
        val current = server
        server = null
        active.set(null)
        runCatching { current?.stop(1_000) }
    }

    private inner class DbkReverseServer(
        address: InetSocketAddress,
    ) : WebSocketServer(address) {
        override fun onStart() = Unit

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val previous = active.getAndSet(conn)
            if (previous != null && previous !== conn) {
                logger.warn { "已有 Koishi 反向连接，关闭旧连接" }
                runCatching { previous.close(1000, "replaced") }
            }
            val dbkSession = createSession(
                sendBytes = { bytes ->
                    if (!conn.isOpen) error("WebSocket 未打开")
                    conn.send(bytes)
                },
                onDead = { reason ->
                    runCatching { conn.close(1000, reason) }
                },
            )
            conn.setAttachment(dbkSession)
            val scope = scope ?: return
            scope.launch {
                runCatching {
                    attachSession(dbkSession)
                    handshake(dbkSession)
                }.onFailure { error ->
                    logger.warn(error) { "Koishi 反向握手失败" }
                    runCatching { conn.close(1000, error.message) }
                }
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            conn.session()?.close("ws close code=$code")
            if (active.compareAndSet(conn, null)) {
                markAccountsUnavailable("反向 WebSocket 已关闭：code=$code reason=$reason")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            conn.session()?.onTextRejected()
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val data = ByteArray(message.remaining())
            message.get(data)
            conn.session()?.onBinary(data)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            logger.warn(ex) { "Koishi 反向 WebSocket 错误" }
        }
    }
}

private fun WebSocket.session(): DbkSession? = getAttachment() as? DbkSession
