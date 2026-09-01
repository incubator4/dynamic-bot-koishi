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
        val current = requireSession() ?: run {
            logger.debug { "message.send: 无可用会话 bot=${botKeyOf(target.platformId, accountId)}" }
            return KoishiSendOutcome.Uncertain("Koishi 未连接，未收到发送响应")
        }
        val params = KoishiOutgoingMapper.toSendParams(
            botKey = botKeyOf(target.platformId, accountId),
            target = target,
            message = message,
            replyToMessageId = replyToMessageId,
        )
        logger.debug {
            "message.send: bot=${params.bot_key} target=${params.target?.describe() ?: target.describe()} " +
                "reply=${params.reply_to_message_id.ifBlank { "-" }} units=${params.unitsSummary()}"
        }
        val outcome = runCatching {
            current.call(
                method = DbkMethods.MESSAGE_SEND,
                request = params,
                responseAdapter = SendResult.ADAPTER,
                timeoutMs = DBK_SEND_TIMEOUT_MS,
            ).toOutcome()
        }.getOrElse { error -> error.toSendOutcome() }
        logger.debug { "message.send: ${outcome.describe()}" }
        return outcome
    }

    override suspend fun recallMessage(accountId: String, target: TargetAddress, messageId: String) {
        val current = requireSession() ?: error("Koishi 未连接")
        logger.debug {
            "message.recall: bot=${botKeyOf(target.platformId, accountId)} target=${target.describe()} message=$messageId"
        }
        val result = current.call(
            method = DbkMethods.MESSAGE_RECALL,
            request = RecallParams(
                bot_key = botKeyOf(target.platformId, accountId),
                target = target.toDbkTarget(),
                message_id = messageId,
            ),
            responseAdapter = RecallResult.ADAPTER,
            timeoutMs = DBK_RECALL_TIMEOUT_MS,
        )
        logger.debug {
            "message.recall: status=${result.status} retryable=${result.retryable} reason=${result.reason.ifBlank { "-" }}"
        }
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
        val account = accounts.values.firstOrNull { it.accountId == accountId } ?: run {
            logger.debug { "targets.list: 账号不存在 accountId=$accountId" }
            return emptyList()
        }
        val current = requireSession() ?: run {
            logger.debug { "targets.list: 无可用会话 bot=${account.botKey}" }
            return emptyList()
        }
        logger.debug { "targets.list: bot=${account.botKey} kind=${kind?.name ?: "*"}" }
        val response = current.call(
            method = DbkMethods.TARGETS_LIST,
            request = ListTargetsRequest(
                bot_key = account.botKey,
                kind = kind?.toDbk() ?: dbk.v1.TargetKind.TARGET_KIND_UNSPECIFIED,
            ),
            responseAdapter = ListTargetsResponse.ADAPTER,
        )
        val mapped = response.targets.mapNotNull { info ->
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
                avatar = info.avatar.trim(),
            )
        }
        logger.debug {
            "targets.list: count=${mapped.size} incomplete=${response.incomplete} dropped=${response.targets.size - mapped.size}"
        }
        return mapped
    }

    override suspend fun getTarget(address: TargetAddress): KoishiTargetCandidate? {
        val account = accountFor(address) ?: run {
            logger.debug { "targets.get: 无匹配账号 ${address.describe()}" }
            return null
        }
        val current = requireSession() ?: run {
            logger.debug { "targets.get: 无可用会话 bot=${account.botKey}" }
            return null
        }
        logger.debug { "targets.get: bot=${account.botKey} target=${address.describe()}" }
        val response = current.call(
            method = DbkMethods.TARGETS_GET,
            request = GetTargetRequest(
                bot_key = account.botKey,
                target = address.toDbkTarget(),
            ),
            responseAdapter = GetTargetResponse.ADAPTER,
        )
        if (response.unresolved) {
            logger.debug { "targets.get: unresolved ${address.describe()}" }
            return null
        }
        val info = response.target ?: run {
            logger.debug { "targets.get: 空 target ${address.describe()}" }
            return null
        }
        val target = info.target ?: return null
        val kind = target.kind.toCore() ?: return null
        val id = target.id.trim().ifBlank { address.externalId }
        logger.debug {
            "targets.get: resolved kind=${kind.name} id=$id name=${info.name.ifBlank { "-" }} bots=${info.bot_keys.joinToString(",").ifBlank { "-" }}"
        }
        return KoishiTargetCandidate(
            id = id,
            name = info.name.trim().ifBlank { id },
            accountId = account.accountId,
            platformId = account.platformId,
            kind = kind,
            avatar = info.avatar.trim(),
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
        logger.debug { "DBK session attached" }
    }

    protected suspend fun handshake(session: DbkSession) {
        val response = session.hello(config.accessToken, DBK_APP_VERSION)
        replaceBots(response.bots)
        logger.info {
            "DBK 握手完成：bots=${response.bots.size} gateway=${response.gateway_version.ifBlank { "-" }}"
        }
        logger.debug {
            "session.hello: protocol=${response.protocol_version.ifBlank { "-" }} keys=${
                response.bots.joinToString(",") { it.bot_key.ifBlank { "-" } }.ifBlank { "-" }
            }"
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
            logger.debug {
                "bots.list: count=${response.bots.size} keys=${
                    response.bots.joinToString(",") { it.bot_key.ifBlank { "-" } }.ifBlank { "-" }
                }"
            }
        }.onFailure { error ->
            logger.debug(error) { "DBK bots.list 失败" }
        }
    }

    private fun replaceBots(bots: List<dbk.v1.Bot>) {
        val next = bots.mapNotNull { it.toRuntimeAccount() }.associateBy { it.botKey }
        if (next.size != bots.size) {
            logger.debug { "bots 快照有 ${bots.size - next.size} 条无法映射" }
        }
        accounts.keys.retainAll(next.keys)
        next.forEach { (key, account) -> accounts[key] = account }
        logger.debug {
            "bots 缓存：${
                next.values.joinToString(",") { "${it.botKey}/${it.state}" }.ifBlank { "-" }
            }"
        }
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
        logger.debug {
            "bot.changed: ${event.type} ${bot.bot_key.ifBlank { "-" }} status=${bot.status}"
        }
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
        logger.debug {
            "message.created: bot=${incoming.bot_key.ifBlank { "-" }} " +
                "target=${incoming.target?.describe() ?: "-"} " +
                "message=${incoming.message_id.ifBlank { "-" }} sender=${incoming.sender_id.ifBlank { "-" }}"
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

    private fun KoishiSendOutcome.describe(): String = when (this) {
        is KoishiSendOutcome.Accepted -> "OK id=${sinkMessageId ?: "-"}"
        is KoishiSendOutcome.Partial ->
            "PARTIAL ids=${sinkMessageIds.joinToString(",").ifBlank { "-" }} reason=$reason"
        is KoishiSendOutcome.Uncertain -> "UNKNOWN reason=$reason"
        is KoishiSendOutcome.Failed -> "FAILED retryable=$retryable reason=$reason"
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
