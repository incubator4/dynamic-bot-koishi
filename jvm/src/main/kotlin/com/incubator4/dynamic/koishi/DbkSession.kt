package com.incubator4.dynamic.koishi

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import dbk.v1.Frame
import dbk.v1.FrameOp
import dbk.v1.RpcError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<DbkSession>()

internal class DbkSession(
    private val sendBytes: (ByteArray) -> Unit,
    private val onEvent: (method: String, payload: ByteString) -> Unit,
    private val onDead: (reason: String) -> Unit,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Frame>>()
    private val nextId = AtomicLong(1)
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val handshook = AtomicBoolean(false)
    private val lastInboundAt = AtomicLong(nowMillis())
    private var heartbeatJob: Job? = null

    val isHandshook: Boolean
        get() = handshook.get() && !closed.get()

    fun onBinary(bytes: ByteArray) {
        if (closed.get()) return
        lastInboundAt.set(nowMillis())
        val frame = runCatching { Frame.ADAPTER.decode(bytes) }.getOrElse { error ->
            logger.warn(error) { "DBK 帧解码失败" }
            fail("DBK 帧无法解码")
            return
        }
        handle(frame)
    }

    fun onTextRejected() {
        fail("DBK 只接受 protobuf 二进制帧")
    }

    fun close(reason: String = "closed") {
        if (!closed.compareAndSet(false, true)) return
        handshook.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(DbkRpcException(dbk.v1.ErrorCode.ERROR_CODE_INTERNAL, reason))
        }
        pending.clear()
        logger.debug { "DBK session closed: $reason" }
    }

    suspend fun hello(token: String, appVersion: String): dbk.v1.HelloResponse {
        val response = call(
            method = DbkMethods.SESSION_HELLO,
            request = dbk.v1.HelloRequest(
                token = token,
                app_version = appVersion,
                protocol_version = DBK_PROTOCOL_VERSION,
            ),
            responseAdapter = dbk.v1.HelloResponse.ADAPTER,
            timeoutMs = DBK_HELLO_TIMEOUT_MS,
        )
        val remoteVersion = response.protocol_version.trim()
        if (remoteVersion.isNotBlank() && remoteVersion != DBK_PROTOCOL_VERSION) {
            throw DbkRpcException(
                dbk.v1.ErrorCode.ERROR_CODE_PROTOCOL,
                "DBK 协议版本不匹配：local=$DBK_PROTOCOL_VERSION remote=$remoteVersion",
            )
        }
        handshook.set(true)
        startHeartbeat()
        return response
    }

    suspend fun <Res : Message<*, *>> call(
        method: String,
        request: Message<*, *>,
        responseAdapter: ProtoAdapter<Res>,
        timeoutMs: Long = DBK_RPC_TIMEOUT_MS,
    ): Res {
        if (closed.get()) {
            throw DbkRpcException(dbk.v1.ErrorCode.ERROR_CODE_INTERNAL, "DBK 连接已关闭")
        }
        if (method != DbkMethods.SESSION_HELLO && !handshook.get()) {
            throw DbkRpcException(dbk.v1.ErrorCode.ERROR_CODE_PROTOCOL, "DBK 尚未完成握手")
        }
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<Frame>()
        pending[id] = deferred
        logger.debug { "DBK CALL $method id=$id" }
        try {
            sendFrame(
                Frame(
                    op = FrameOp.FRAME_OP_CALL,
                    id = id,
                    method = method,
                    payload = request.encodeByteString(),
                ),
            )
            val frame = withTimeout(timeoutMs) { deferred.await() }
            if (frame.op == FrameOp.FRAME_OP_ERROR) {
                val error = frame.error ?: RpcError()
                logger.warn { "DBK RPC 失败：method=$method code=${error.code} ${error.detail}" }
                throw DbkRpcException(
                    code = error.code,
                    message = error.detail.trim().ifBlank { "DBK RPC 失败：method=$method code=${error.code}" },
                )
            }
            if (frame.op != FrameOp.FRAME_OP_OK) {
                throw DbkRpcException(dbk.v1.ErrorCode.ERROR_CODE_PROTOCOL, "DBK 响应 op 无效：${frame.op}")
            }
            return responseAdapter.decode(frame.payload)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            logger.debug { "DBK 调用超时：method=$method" }
            throw DbkRpcException(dbk.v1.ErrorCode.ERROR_CODE_INTERNAL, "DBK 调用超时：method=$method")
        } finally {
            pending.remove(id)
        }
    }

    private fun handle(frame: Frame) {
        when (frame.op) {
            FrameOp.FRAME_OP_PING -> scope.launch {
                sendFrame(Frame(op = FrameOp.FRAME_OP_PONG, id = frame.id))
            }
            FrameOp.FRAME_OP_PONG -> Unit
            FrameOp.FRAME_OP_OK, FrameOp.FRAME_OP_ERROR -> {
                val deferred = pending.remove(frame.id)
                if (deferred == null) {
                    logger.debug { "忽略未知 DBK 响应：id=${frame.id} op=${frame.op}" }
                } else {
                    deferred.complete(frame)
                }
            }
            FrameOp.FRAME_OP_EVENT -> {
                if (!handshook.get()) {
                    logger.debug { "握手前丢弃事件：method=${frame.method}" }
                    return
                }
                logger.debug { "DBK EVENT ${frame.method} seq=${frame.seq}" }
                onEvent(frame.method, frame.payload)
            }
            FrameOp.FRAME_OP_CALL -> logger.warn { "App 端忽略对端 CALL：method=${frame.method}" }
            FrameOp.FRAME_OP_UNSPECIFIED -> logger.warn { "忽略未指定 op 的 DBK 帧" }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastInboundAt.set(nowMillis())
        heartbeatJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(DBK_PING_INTERVAL_MS)
                if (closed.get()) return@launch
                val silentFor = nowMillis() - lastInboundAt.get()
                if (silentFor >= DBK_PONG_TIMEOUT_MS) {
                    fail("DBK 心跳超时")
                    return@launch
                }
                runCatching { sendFrame(Frame(op = FrameOp.FRAME_OP_PING)) }
            }
        }
    }

    private suspend fun sendFrame(frame: Frame) {
        sendMutex.withLock {
            if (closed.get()) return
            sendBytes(Frame.ADAPTER.encode(frame))
        }
    }

    private fun fail(reason: String) {
        if (closed.get()) return
        onDead(reason)
        close(reason)
    }
}
