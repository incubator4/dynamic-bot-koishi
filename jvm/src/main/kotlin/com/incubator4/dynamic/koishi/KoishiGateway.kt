package com.incubator4.dynamic.koishi

import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRouteState

internal const val KOISHI_PLUGIN_ID: String = "koishi-gateway"

public data class KoishiIncomingMessage(
    val platformId: String,
    val targetKind: TargetKind,
    val chatId: String,
    val senderId: String,
    val text: String,
    val botAccountId: String? = null,
    val messageId: String = "",
    val timestamp: Long = 0,
    val segments: List<IncomingMessageSegment> = emptyList(),
    val rawFormat: String = "",
    val rawPayload: String = "",
    val mentionedAccountIds: Set<String> = emptySet(),
)

public data class KoishiTargetCandidate(
    val id: String,
    val name: String,
    val accountId: String,
    val platformId: PlatformId,
    val kind: TargetKind,
)

public data class KoishiRuntimeAccount(
    val accountId: String,
    val platformId: PlatformId,
    val name: String = accountId,
    val state: MessageSinkRouteState = MessageSinkRouteState.READY,
    val features: Set<String> = emptySet(),
    val avatar: String = "",
    val botKey: String = "${platformId.value}:$accountId",
)

public sealed interface KoishiSendOutcome {
    public data class Accepted(val sinkMessageId: String? = null) : KoishiSendOutcome
    public data class Partial(
        val sinkMessageIds: List<String>,
        val reason: String,
    ) : KoishiSendOutcome
    public data class Uncertain(val reason: String) : KoishiSendOutcome
    public data class Failed(
        val reason: String,
        val retryable: Boolean = true,
    ) : KoishiSendOutcome
}

public interface KoishiGateway {
    public fun connect(onIncomingMessage: (KoishiIncomingMessage) -> Unit)
    public fun cachedAccounts(): List<KoishiRuntimeAccount>
    public suspend fun availableAccounts(): List<KoishiRuntimeAccount>
    public suspend fun sendMessage(
        accountId: String,
        target: TargetAddress,
        message: Message,
        replyToMessageId: String?,
    ): KoishiSendOutcome
    public suspend fun recallMessage(accountId: String, target: TargetAddress, messageId: String)
    public suspend fun listTargets(accountId: String, kind: TargetKind?): List<KoishiTargetCandidate>
    public suspend fun getTarget(address: TargetAddress): KoishiTargetCandidate? = null
    public suspend fun close()
}

public class NoopKoishiGateway : KoishiGateway {
    override fun connect(onIncomingMessage: (KoishiIncomingMessage) -> Unit) {
    }

    override fun cachedAccounts(): List<KoishiRuntimeAccount> = emptyList()

    override suspend fun availableAccounts(): List<KoishiRuntimeAccount> = emptyList()

    override suspend fun sendMessage(
        accountId: String,
        target: TargetAddress,
        message: Message,
        replyToMessageId: String?,
    ): KoishiSendOutcome = KoishiSendOutcome.Uncertain("Koishi 未运行，未收到发送响应")

    override suspend fun recallMessage(accountId: String, target: TargetAddress, messageId: String) {
    }

    override suspend fun listTargets(accountId: String, kind: TargetKind?): List<KoishiTargetCandidate> = emptyList()

    override suspend fun close() {
    }
}

internal object KoishiGatewayFactory {
    fun create(config: KoishiConfig): KoishiGateway {
        KoishiConfigForm.validate(config)
        return when (config.mode) {
            KoishiConnectionMode.FORWARD_WS -> ForwardWsKoishiGateway(config)
            KoishiConnectionMode.REVERSE_WS -> ReverseWsKoishiGateway(config)
        }
    }
}
