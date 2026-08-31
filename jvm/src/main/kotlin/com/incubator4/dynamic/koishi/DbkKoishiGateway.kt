package com.incubator4.dynamic.koishi

import dbk.v1.BotChangeType
import dbk.v1.BotChangedEvent
import dbk.v1.GetTargetRequest
import dbk.v1.GetTargetResponse
import dbk.v1.IncomingMessage as DbkIncomingMessage
import dbk.v1.ListBotsRequest
import dbk.v1.ListBotsResponse
import dbk.v1.ListTargetsRequest
import dbk.v1.ListTargetsResponse
import dbk.v1.RecallParams
import dbk.v1.RecallResult
import dbk.v1.SendResult
import dbk.v1.SendStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<DbkKoishiGateway>()

internal abstract class DbkKoishiGateway(
    protected val config: KoishiConfig,
) : KoishiGateway {

    private val accounts = ConcurrentHashMap<String, KoishiRuntimeAccount>()
    private val sessionLock = Mutex()
    @Volatile
    private var session: DbkSession? = null
    @Volatile
    private var incomingMessageHandler: ((KoishiIncomingMessage) -> Unit)? = null
    @Volatile
    protected var closing: Boolean = false
    protected var scope: CoroutineScope? = null

    override fun connect(onIncomingMessage: (KoishiIncomingMessage) -> Unit) {
        if (scope != null) return
        closing = false
        incomingMessageHandler = onIncomingMessage
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startTransport()
    }

    override suspend fun close() {
        closing = true
        incomingMessageHandler = null
        sessionLock.withLock {
            session?.close("plugin stop")
            session = null
        }
        stopTransport()
        scope?.cancel()
        scope = null
        accounts.clear()
    }

    override fun cachedAccounts(): List<KoishiRuntimeAccount> {
        return accounts.values
            .sortedWith(compareBy<KoishiRuntimeAccount> { it.platformId.value }.thenBy { it.accountId })
    }

    override suspend fun availableAccounts(): List<KoishiRuntimeAccount> {
        refreshBots()
        return cachedAccounts()
    }

    override suspend fun sendMessage(
        accountId: String,
        target: TargetAddress,
        message: Message,
        replyToMessageId: String?,
    ): KoishiSendOutcome {
        val current = requireSession() ?: return KoishiSendOutcome.Uncertain("Koishi 未连接，未收到发送响应")
        val params = KoishiOutgoingMapper.toSendParams(
            botKey = botKeyOf(target.platformId, accountId),
            target = target,
            message = message,
            replyToMessageId = replyToMessageId,
        )
        return runCatching {
            current.call(
                method = DbkMethods.MESSAGE_SEND,
                request = params,
                responseAdapter = SendResult.ADAPTER,
                timeoutMs = DBK_SEND_TIMEOUT_MS,
            ).toOutcome()
        }.getOrElse { error -> error.toSendOutcome() }
    }

    override suspend fun recallMessage(accountId: String, target: TargetAddress, messageId: String) {
        val current = requireSession() ?: error("Koishi 未连接")
        val result = current.call(
            method = DbkMethods.MESSAGE_RECALL,
            request = RecallParams(
                bot_key = botKeyOf(target.platformId, accountId),
                target = target.toDbkTarget(),
                message_id = messageId,
            ),
            responseAdapter = RecallResult.ADAPTER,
        )
        when (result.status) {
            SendStatus.SEND_STATUS_OK -> Unit
            SendStatus.SEND_STATUS_UNKNOWN -> error(result.reason.ifBlank { "Koishi 撤回状态未知" })
            SendStatus.SEND_STATUS_FAILED,
            SendStatus.SEND_STATUS_PARTIAL,
            SendStatus.SEND_STATUS_UNSPECIFIED,
            -> error(result.reason.ifBlank { "Koishi 消息撤回失败" })
        }
    }

    override suspend fun listTargets(accountId: String, kind: TargetKind?): List<KoishiTargetCandidate> {
        val account = accounts.values.firstOrNull { it.accountId == accountId } ?: return emptyList()
        val current = requireSession() ?: return emptyList()
        val response = current.call(
            method = DbkMethods.TARGETS_LIST,
            request = ListTargetsRequest(
                bot_key = account.botKey,
                kind = kind?.toDbk() ?: dbk.v1.TargetKind.TARGET_KIND_UNSPECIFIED,
            ),
            responseAdapter = ListTargetsResponse.ADAPTER,
        )
        return response.targets.mapNotNull { info ->
            val target = info.target ?: return@mapNotNull null
            val targetKind = target.kind.toCore() ?: return@mapNotNull null
            val id = target.id.trim()
            if (id.isBlank()) return@mapNotNull null
            KoishiTargetCandidate(
                id = id,
                name = info.name.trim().ifBlank { id },
                accountId = account.accountId,
                platformId = account.platformId,
                kind = targetKind,
            )
        }
    }

    override suspend fun getTarget(address: TargetAddress): KoishiTargetCandidate? {
        val account = accountFor(address) ?: return null
        val current = requireSession() ?: return null
        val response = current.call(
            method = DbkMethods.TARGETS_GET,
            request = GetTargetRequest(
                bot_key = account.botKey,
                target = address.toDbkTarget(),
            ),
            responseAdapter = GetTargetResponse.ADAPTER,
        )
        if (response.unresolved) return null
        val info = response.target ?: return null
        val target = info.target ?: return null
        val kind = target.kind.toCore() ?: return null
        val id = target.id.trim().ifBlank { address.externalId }
        return KoishiTargetCandidate(
            id = id,
            name = info.name.trim().ifBlank { id },
            accountId = account.accountId,
            platformId = account.platformId,
            kind = kind,
        )
    }

    protected abstract fun startTransport()

    protected abstract fun stopTransport()

    protected fun currentScope(): CoroutineScope {
        return scope ?: error("Koishi 网关尚未启动")
    }

    protected suspend fun attachSession(next: DbkSession) {
        sessionLock.withLock {
            session?.close("replaced")
            session = next
        }
    }

    protected suspend fun handshake(session: DbkSession) {
        val response = session.hello(config.accessToken, DBK_APP_VERSION)
        replaceBots(response.bots)
        logger.info {
            "DBK 握手完成：bots=${response.bots.size} gateway=${response.gateway_version.ifBlank { "-" }}"
        }
    }

    protected fun createSession(sendBytes: (ByteArray) -> Unit, onDead: (reason: String) -> Unit): DbkSession {
        return DbkSession(
            sendBytes = sendBytes,
            onEvent = ::onEvent,
            onDead = onDead,
            scope = currentScope(),
        )
    }

    protected fun markAccountsUnavailable(reason: String) {
        if (accounts.isEmpty()) {
            logger.warn { "DBK 连接不可用：$reason" }
            return
        }
        accounts.replaceAll { _, account -> account.copy(state = MessageSinkRouteState.UNAVAILABLE) }
        logger.warn { "DBK 账号已标为不可用：$reason" }
    }

    private suspend fun refreshBots() {
        val current = session ?: return
        if (!current.isHandshook || closing) return
        runCatching {
            current.call(
                method = DbkMethods.BOTS_LIST,
                request = ListBotsRequest(),
                responseAdapter = ListBotsResponse.ADAPTER,
            )
        }.onSuccess { response ->
            replaceBots(response.bots)
        }.onFailure { error ->
            logger.debug(error) { "DBK bots.list 失败" }
        }
    }

    private fun replaceBots(bots: List<dbk.v1.Bot>) {
        val next = bots.mapNotNull { it.toRuntimeAccount() }.associateBy { it.botKey }
        accounts.keys.retainAll(next.keys)
        next.forEach { (key, account) -> accounts[key] = account }
    }

    private fun onEvent(method: String, payload: ByteString) {
        when (method) {
            DbkMethods.EVENT_BOT_CHANGED -> handleBotChanged(payload)
            DbkMethods.EVENT_MESSAGE_CREATED -> handleIncoming(payload)
            else -> logger.debug { "忽略未知 DBK 事件：method=$method" }
        }
    }

    private fun handleBotChanged(payload: ByteString) {
        val event = runCatching { BotChangedEvent.ADAPTER.decode(payload) }.getOrElse {
            logger.warn(it) { "bot.changed 解码失败" }
            return
        }
        val bot = event.bot ?: return
        when (event.type) {
            BotChangeType.BOT_CHANGE_TYPE_REMOVED -> {
                val key = bot.bot_key.trim().ifBlank {
                    val parsed = parseBotKey(bot.bot_key)
                    if (parsed != null) "${parsed.first}:${parsed.second}" else ""
                }.ifBlank {
                    val platform = bot.platform.trim()
                    val selfId = bot.self_id.trim()
                    if (platform.isNotBlank() && selfId.isNotBlank()) "$platform:$selfId" else ""
                }
                if (key.isNotBlank()) accounts.remove(key)
            }
            BotChangeType.BOT_CHANGE_TYPE_ADDED,
            BotChangeType.BOT_CHANGE_TYPE_UPDATED,
            BotChangeType.BOT_CHANGE_TYPE_UNSPECIFIED,
            -> bot.toRuntimeAccount()?.let { accounts[it.botKey] = it }
        }
    }

    private fun handleIncoming(payload: ByteString) {
        val incoming = runCatching { DbkIncomingMessage.ADAPTER.decode(payload) }.getOrElse {
            logger.warn(it) { "message.created 解码失败" }
            return
        }
        val mapped = KoishiIncomingMapper.fromProto(incoming)
        if (mapped == null) {
            logger.debug { "丢弃无法映射的入站消息：messageId=${incoming.message_id}" }
            return
        }
        incomingMessageHandler?.invoke(mapped)
    }

    private fun requireSession(): DbkSession? {
        val current = session
        return current?.takeIf { it.isHandshook && !closing }
    }

    private fun accountFor(address: TargetAddress): KoishiRuntimeAccount? {
        val preferred = address.accountId?.trim()?.takeIf { it.isNotBlank() }
        return accounts.values.firstOrNull { account ->
            account.platformId == address.platformId &&
                account.state == MessageSinkRouteState.READY &&
                (preferred == null || account.accountId == preferred)
        }
    }

    private fun SendResult.toOutcome(): KoishiSendOutcome {
        val ids = receipts.map { it.message_id.trim() }.filter { it.isNotBlank() }
        val reason = reason.trim()
        return when (status) {
            SendStatus.SEND_STATUS_OK -> KoishiSendOutcome.Accepted(sinkMessageId = ids.firstOrNull())
            SendStatus.SEND_STATUS_PARTIAL -> KoishiSendOutcome.Partial(
                sinkMessageIds = ids,
                reason = reason.ifBlank { "Koishi 消息部分发送成功" },
            )
            SendStatus.SEND_STATUS_UNKNOWN -> KoishiSendOutcome.Uncertain(
                reason = reason.ifBlank { "Koishi 发送状态未知" },
            )
            SendStatus.SEND_STATUS_FAILED -> KoishiSendOutcome.Failed(
                reason = reason.ifBlank { "Koishi 消息发送失败" },
                retryable = retryable,
            )
            SendStatus.SEND_STATUS_UNSPECIFIED -> KoishiSendOutcome.Failed(
                reason = reason.ifBlank { "Koishi 发送状态未指定" },
                retryable = false,
            )
        }
    }

    private fun Throwable.toSendOutcome(): KoishiSendOutcome {
        val text = message?.trim().orEmpty()
        val uncertain = this is DbkRpcException && (
            "超时" in text || "未连接" in text || "尚未完成握手" in text
        ) || "超时" in text
        return if (uncertain) {
            KoishiSendOutcome.Uncertain(reason = text.ifBlank { "Koishi 发送状态未知" })
        } else if (this is DbkRpcException) {
            KoishiSendOutcome.Failed(reason = text.ifBlank { "Koishi 消息发送失败" }, retryable = false)
        } else {
            KoishiSendOutcome.Failed(reason = text.ifBlank { "Koishi 消息发送失败" }, retryable = false)
        }
    }
}
