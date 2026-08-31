package com.incubator4.dynamic.koishi

import dbk.v1.Bot
import dbk.v1.BotStatus
import dbk.v1.Frame
import dbk.v1.FrameOp
import dbk.v1.HelloRequest
import dbk.v1.HelloResponse
import dbk.v1.ListBotsRequest
import dbk.v1.ListBotsResponse
import dbk.v1.SendParams
import dbk.v1.SendReceipt
import dbk.v1.SendResult
import dbk.v1.SendStatus
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class DbkLoopbackTest {
    @Test
    fun `forward gateway should hello list bots and send`() = runBlocking {
        val server = DbkTestServer()
        server.start()
        try {
            withTimeout(5_000) {
                while (server.port <= 0) delay(20)
            }
            val gateway = ForwardWsKoishiGateway(
                KoishiConfig(url = "ws://127.0.0.1:${server.port}", accessToken = "token", reconnect = false),
            )
            try {
                gateway.connect { }
                val account = withTimeout(5_000) {
                    var ready: KoishiRuntimeAccount? = null
                    while (ready == null) {
                        ready = gateway.availableAccounts().firstOrNull {
                            it.state == MessageSinkRouteState.READY
                        }
                        if (ready == null) delay(50)
                    }
                    checkNotNull(ready)
                }
                assertEquals("42", account.accountId)
                assertEquals("discord", account.platformId.value)

                val outcome = gateway.sendMessage(
                    accountId = "42",
                    target = TargetAddress.of("discord", TargetKind.CHANNEL, "111"),
                    message = Message(
                        id = "m1",
                        time = 1,
                        targets = listOf(TargetAddress.of("discord", TargetKind.CHANNEL, "111")),
                        batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
                    ),
                    replyToMessageId = null,
                )
                val accepted = assertIs<KoishiSendOutcome.Accepted>(outcome)
                assertEquals("sink-1", accepted.sinkMessageId)
            } finally {
                gateway.close()
            }
        } finally {
            server.stop(1_000)
        }
    }
}

private class DbkTestServer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
    private val seq = AtomicLong(0)

    override fun onStart() = Unit

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = Unit

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

    override fun onMessage(conn: WebSocket, message: String) {
        conn.close(1002, "binary only")
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val data = ByteArray(message.remaining())
        message.get(data)
        val frame = Frame.ADAPTER.decode(data)
        if (frame.op != FrameOp.FRAME_OP_CALL) {
            if (frame.op == FrameOp.FRAME_OP_PING) {
                conn.send(Frame.ADAPTER.encode(Frame(op = FrameOp.FRAME_OP_PONG, id = frame.id)))
            }
            return
        }
        val payload = when (frame.method) {
            DbkMethods.SESSION_HELLO -> {
                val hello = HelloRequest.ADAPTER.decode(frame.payload)
                if (hello.token != "token") {
                    conn.send(
                        Frame.ADAPTER.encode(
                            Frame(
                                op = FrameOp.FRAME_OP_ERROR,
                                id = frame.id,
                                error = dbk.v1.RpcError(
                                    code = dbk.v1.ErrorCode.ERROR_CODE_UNAUTHORIZED,
                                    detail = "bad token",
                                ),
                            ),
                        ),
                    )
                    return
                }
                HelloResponse.ADAPTER.encodeByteString(
                    HelloResponse(
                        protocol_version = DBK_PROTOCOL_VERSION,
                        gateway_version = "test",
                        bots = listOf(
                            Bot(
                                bot_key = "discord:42",
                                platform = "discord",
                                self_id = "42",
                                name = "Discord Bot",
                                status = BotStatus.BOT_STATUS_READY,
                                features = listOf("message.recall", "targets.list"),
                            ),
                        ),
                    ),
                )
            }
            DbkMethods.BOTS_LIST -> {
                ListBotsRequest.ADAPTER.decode(frame.payload)
                ListBotsResponse.ADAPTER.encodeByteString(
                    ListBotsResponse(
                        bots = listOf(
                            Bot(
                                bot_key = "discord:42",
                                platform = "discord",
                                self_id = "42",
                                name = "Discord Bot",
                                status = BotStatus.BOT_STATUS_READY,
                            ),
                        ),
                    ),
                )
            }
            DbkMethods.MESSAGE_SEND -> {
                SendParams.ADAPTER.decode(frame.payload)
                SendResult.ADAPTER.encodeByteString(
                    SendResult(
                        status = SendStatus.SEND_STATUS_OK,
                        receipts = listOf(SendReceipt(message_id = "sink-1", recallable = true)),
                    ),
                )
            }
            else -> {
                conn.send(
                    Frame.ADAPTER.encode(
                        Frame(
                            op = FrameOp.FRAME_OP_ERROR,
                            id = frame.id,
                            error = dbk.v1.RpcError(
                                code = dbk.v1.ErrorCode.ERROR_CODE_UNSUPPORTED,
                                detail = frame.method,
                            ),
                        ),
                    ),
                )
                return
            }
        }
        conn.send(
            Frame.ADAPTER.encode(
                Frame(
                    op = FrameOp.FRAME_OP_OK,
                    id = frame.id,
                    payload = payload,
                ),
            ),
        )
        seq.incrementAndGet()
    }

    override fun onError(conn: WebSocket?, ex: Exception) = Unit
}
