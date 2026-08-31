package com.incubator4.dynamic.koishi

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<KoishiGatewayPlugin>()

public class KoishiGatewayPlugin :
    AccountRoutedMessageSinkPlugin,
    ConfigurablePlugin<KoishiConfig> {

    private var pluginId: String = KOISHI_PLUGIN_ID
    private var config: KoishiConfig = KoishiConfig()
    private var gateway: KoishiGateway = NoopKoishiGateway()
    private var incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { }
    private lateinit var pluginScope: CoroutineScope
    private var incomingScope: CoroutineScope? = null
    private var incomingJob: Job? = null
    private var running: Boolean = false

    override val configId: String
        get() = pluginId
    override val configName: String = "Koishi 网关"
    override val configDescription: String = "Koishi 进程连接配置；账号由 Koishi 自动发现。"
    override val configClass = KoishiConfig::class
    override val configFormSpec = KoishiConfigForm.spec

    override val transportId: String = "koishi"
    override val transportName: String = "Koishi"
    override val supportedTargetPlatforms: Set<PlatformId>
        get() = gateway.cachedAccounts()
            .map { it.platformId }
            .filterNot { it == QQ_PLATFORM_ID }
            .toSet()
    override val supportedTargetKinds: Set<TargetKind>
        get() = gateway.cachedAccounts()
            .filterNot { it.platformId == QQ_PLATFORM_ID }
            .flatMap { it.targetKinds() }
            .toSet()

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        incomingMessagePublisher = context.incomingMessagePublisher
        pluginScope = context.scope
        config = context.configService.loadOrCreate(pluginId, KoishiConfigForm.migrations) { KoishiConfig() }
            .normalized()
        KoishiConfigForm.validate(config)
        logger.info { "Koishi 配置已加载：pluginId=$pluginId，mode=${config.mode} endpoint=${config.endpointLabel()}" }
    }

    override suspend fun onStart() {
        if (running) return

        KoishiConfigForm.validate(config)
        gateway = KoishiGatewayFactory.create(config)
        startIncomingScope()
        runCatching {
            gateway.connect(::onIncomingMessage)
        }.onFailure {
            stopIncomingScope()
            gateway.close()
            gateway = NoopKoishiGateway()
            logger.error(it) { "Koishi 启动失败：mode=${config.mode} endpoint=${config.endpointLabel()}" }
            throw it
        }
        running = true
        logger.info { "Koishi 已启动：mode=${config.mode} endpoint=${config.endpointLabel()}" }
    }

    override suspend fun onStop() {
        if (!running) return
        running = false
        stopIncomingScope()
        gateway.close()
        gateway = NoopKoishiGateway()
        logger.info { "Koishi 已停止" }
    }

    override fun currentConfig(): KoishiConfig = config

    override fun applyConfig(next: KoishiConfig): ConfigApplyResult {
        val normalized = next.normalized()
        KoishiConfigForm.validate(normalized)
        val changed = normalized != config
        config = normalized
        return ConfigApplyResult(
            changed = changed,
            restartRequired = changed,
            restartTargets = if (changed) listOf("Koishi 插件") else emptyList(),
            message = if (changed) {
                "Koishi 配置已保存；请重启 Koishi 插件以重新连接"
            } else {
                "Koishi 配置未变化"
            },
        )
    }

    override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
        if (target != null && isExcludedTarget(target)) return emptyList()
        return koishiAccounts()
            .filter { account -> target == null || account.matches(target) }
            .map { account ->
                account.toRoute(if (running) account.state else MessageSinkRouteState.UNAVAILABLE)
            }
    }

    override suspend fun sendMessage(
        request: MessageSendRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("Koishi 未运行")
        if (isExcludedTarget(request.target)) {
            return MessageSendResult.failed("目标平台或类型不受 Koishi 网关支持", retryable = false)
        }
        val route = parseRoute(routeId)
            ?: return MessageSendResult.failed("Koishi 路线 ID 无效：$routeId", retryable = false)
        if (route.platformId != request.target.platformId) {
            return MessageSendResult.failed("Koishi 路线平台与目标不一致", retryable = false)
        }
        if (!isAccountReady(route.accountId, route.platformId)) {
            return MessageSendResult.failed("Koishi 账号不可用：${route.accountId}")
        }

        return when (val outcome = gateway.sendMessage(
            accountId = route.accountId,
            target = request.target,
            message = request.message,
            replyToMessageId = request.replyToMessageId,
        )) {
            is KoishiSendOutcome.Accepted -> MessageSendResult.sent(
                sinkMessageId = outcome.sinkMessageId,
                sinkRouteId = routeId,
                sinkAccountId = route.accountId,
                sinkTransportId = transportId,
            )
            is KoishiSendOutcome.Partial -> MessageSendResult.partiallySent(
                receipts = outcome.sinkMessageIds.map { sinkMessageId ->
                    MessageSendResult.receipt(
                        sinkMessageId = sinkMessageId,
                        sinkRouteId = routeId,
                        sinkAccountId = route.accountId,
                        sinkTransportId = transportId,
                    )
                },
                reason = outcome.reason,
            )
            is KoishiSendOutcome.Uncertain -> MessageSendResult.uncertain(
                reason = outcome.reason,
                sinkRouteId = routeId,
                sinkAccountId = route.accountId,
                sinkTransportId = transportId,
            )
            is KoishiSendOutcome.Failed -> MessageSendResult.failed(
                reason = outcome.reason,
                retryable = outcome.retryable,
            )
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest, routeId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("Koishi 未运行")
        if (isExcludedTarget(request.target)) {
            return MessageRecallResult.failed("目标平台或类型不受 Koishi 网关支持")
        }
        val route = parseRoute(routeId)
            ?: return MessageRecallResult.failed("Koishi 路线 ID 无效：$routeId")
        if (!isAccountReady(route.accountId, route.platformId)) {
            return MessageRecallResult.failed("Koishi 账号不可用：${route.accountId}")
        }
        val messageId = request.sinkMessageId.trim()
        if (messageId.isBlank()) {
            return MessageRecallResult.failed("Koishi 消息 ID 为空，无法撤回")
        }
        return runCatching { gateway.recallMessage(route.accountId, request.target, messageId) }
            .fold(
                onSuccess = { MessageRecallResult.recalled() },
                onFailure = { error -> MessageRecallResult.failed(error.message ?: "Koishi 消息撤回失败") },
            )
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val kinds = kind?.let { setOf(it) }
        val accounts = koishiAccounts()
            .filter { it.state == MessageSinkRouteState.READY }
            .associateBy { it.accountKey() }
        val targets = mutableListOf<MessageTargetCandidate>()
        accounts.values.forEach { account ->
            val listed = runCatching { gateway.listTargets(account.accountId, kind) }
                .getOrElse {
                    logger.warn(it) {
                        "Koishi 目标列表读取失败：accountId=${account.accountId} platform=${account.platformId.value}"
                    }
                    emptyList()
                }
            targets += listed
                .filter { kinds == null || it.kind in kinds }
                .map { candidate ->
                    MessageTargetCandidate(
                        address = TargetAddress.of(
                            platformId = candidate.platformId.value,
                            kind = candidate.kind,
                            externalId = candidate.id,
                            accountId = candidate.accountId,
                        ),
                        name = candidate.name,
                        avatar = candidate.avatar.toAvatarRef(),
                        sources = listOfNotNull(accounts[account.accountKey()]?.toRoute()?.toTargetSource()),
                    )
                }
        }
        return targets
            .groupBy { it.address.stableValue() }
            .values
            .map { duplicates ->
                val first = duplicates.first()
                first.copy(
                    sources = duplicates.flatMap { it.sources }.distinctBy { it.routeId },
                    avatar = duplicates.mapNotNull { it.avatar }.firstOrNull(),
                )
            }
            .sortedWith(compareBy<MessageTargetCandidate> { it.address.kind.name }.thenBy { it.name })
    }

    override suspend fun resolveMessageTarget(address: TargetAddress): MessageTargetCandidate? {
        if (isExcludedTarget(address)) return null
        val listed = runCatching { gateway.getTarget(address) }.getOrNull()
        if (listed != null) {
            return MessageTargetCandidate(
                address = TargetAddress.of(
                    platformId = listed.platformId.value,
                    kind = listed.kind,
                    externalId = listed.id,
                    accountId = listed.accountId,
                ),
                name = listed.name,
                avatar = listed.avatar.toAvatarRef(),
            )
        }
        return MessageTargetCandidate(
            address = address.copy(accountId = address.accountId?.trim()?.takeIf { it.isNotBlank() }),
            name = address.externalId,
        )
    }

    override fun supportsTarget(address: TargetAddress): Boolean {
        if (isExcludedTarget(address)) return false
        return address.platformId in supportedTargetPlatforms
    }

    private fun onIncomingMessage(incoming: KoishiIncomingMessage) {
        if (!running) return

        val incomingMessage = KoishiIncomingMapper.toIncomingMessage(incoming)
        val scope = incomingScope ?: return
        scope.launch {
            if (!running) return@launch
            runCatching {
                incomingMessagePublisher.publish(
                    IncomingMessagePublishRequest(
                        message = incomingMessage,
                        traceId = incoming.traceId(),
                        replyToMessageId = incoming.messageId,
                        receivedAtEpochSeconds = System.currentTimeMillis() / 1000,
                        sourceEventId = incoming.sourceEventId(),
                        dedupeKey = incoming.dedupeKey(),
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn(error) {
                    "Koishi 入站消息提交失败：messageId=${incoming.messageId.ifBlank { "-" }}"
                }
            }
        }
    }

    private fun startIncomingScope() {
        stopIncomingScope()
        val parentJob = pluginScope.coroutineContext[Job]
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        incomingJob = job
        incomingScope = CoroutineScope(pluginScope.coroutineContext.minusKey(Job) + job)
    }

    private fun stopIncomingScope() {
        incomingJob?.cancel("Koishi plugin stopped")
        incomingJob = null
        incomingScope = null
    }

    private fun isExcludedTarget(address: TargetAddress): Boolean {
        if (address.platformId == QQ_PLATFORM_ID) return true
        val accounts = gateway.cachedAccounts().filter { it.platformId == address.platformId }
        val kinds = accounts.flatMap { it.targetKinds() }.toSet()
        return kinds.isNotEmpty() && address.kind !in kinds
    }

    private suspend fun koishiAccounts(): List<KoishiRuntimeAccount> {
        return gateway.availableAccounts().filter { it.platformId != QQ_PLATFORM_ID }
    }

    private suspend fun isAccountReady(accountId: String, platformId: PlatformId): Boolean {
        if (platformId == QQ_PLATFORM_ID) return false
        return koishiAccounts().any {
            it.accountId == accountId &&
                it.platformId == platformId &&
                it.state == MessageSinkRouteState.READY
        }
    }

    private fun KoishiRuntimeAccount.matches(target: TargetAddress): Boolean {
        if (platformId != target.platformId) return false
        val preferredAccount = target.accountId?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return accountId == preferredAccount
    }

    private fun KoishiRuntimeAccount.toRoute(
        state: MessageSinkRouteState = MessageSinkRouteState.READY,
    ): MessageSinkRoute = MessageSinkRoute(
        routeId = routeId(platformId, accountId),
        transportId = transportId,
        transportName = transportName,
        targetPlatformId = platformId,
        accountId = accountId,
        accountName = name,
        accountAvatar = avatar.toAvatarRef(),
        enabled = true,
        state = state,
    )

    private fun routeId(platformId: PlatformId, accountId: String): String {
        return "$transportId:${platformId.value}:$accountId"
    }

    private fun parseRoute(routeId: String): ParsedRoute? {
        val prefix = "$transportId:"
        if (!routeId.startsWith(prefix)) return null
        val rest = routeId.removePrefix(prefix)
        val separator = rest.indexOf(':')
        if (separator <= 0 || separator == rest.lastIndex) return null
        val platform = rest.take(separator).trim()
        val accountId = rest.substring(separator + 1).trim()
        if (platform.isBlank() || accountId.isBlank()) return null
        return ParsedRoute(platformId = PlatformId.of(platform), accountId = accountId)
    }

    private fun KoishiIncomingMessage.sourceEventId(): String {
        return listOf(targetKind.name, chatId, messageId).joinToString(":")
    }

    private fun KoishiIncomingMessage.dedupeKey(): String {
        val botKey = listOf(platformId, botAccountId.orEmpty()).joinToString(":")
        return listOf(
            KOISHI_PLUGIN_ID,
            botKey,
            targetKind.name,
            chatId,
            messageId,
        ).joinToString(":")
    }

    private fun KoishiIncomingMessage.traceId(): String {
        return dedupeKey().sha256Hex()
    }

    private fun KoishiRuntimeAccount.accountKey(): String = "${platformId.value}:$accountId"

    private data class ParsedRoute(
        val platformId: PlatformId,
        val accountId: String,
    )
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private val QQ_PLATFORM_ID: PlatformId = PlatformId.of("qq")

private fun String.toAvatarRef(): MediaRef? {
    val uri = trim()
    if (uri.isBlank()) return null
    return MediaRef(uri = uri, kind = MediaKind.AVATAR)
}
