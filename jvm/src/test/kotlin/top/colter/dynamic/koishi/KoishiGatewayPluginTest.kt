package top.colter.dynamic.koishi

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class KoishiGatewayPluginTest {
    @Test
    fun `list routes should keep runtime account unavailable state`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        plugin.setPrivate("gateway", FakeGateway(MessageSinkRouteState.UNAVAILABLE))
        plugin.setPrivate("running", true)

        val route = plugin.listMessageSinkRoutes(
            TargetAddress.of("discord", TargetKind.CHANNEL, "111"),
        ).single()

        assertEquals(MessageSinkRouteState.UNAVAILABLE, route.state)
        assertEquals("42", route.accountId)
        assertEquals("koishi:discord:42", route.routeId)
    }

    @Test
    fun `supported platforms should come from koishi accounts`() {
        val plugin = KoishiGatewayPlugin()
        plugin.setPrivate(
            "gateway",
            FakeGateway(
                MessageSinkRouteState.READY,
                accounts = listOf(
                    runtimeAccount("discord", "42"),
                    runtimeAccount("telegram", "42"),
                    runtimeAccount("matrix", "7"),
                    runtimeAccount("qq", "10001"),
                ),
            ),
        )

        assertEquals(
            setOf(PlatformId.of("discord"), PlatformId.of("telegram"), PlatformId.of("matrix")),
            plugin.supportedTargetPlatforms,
        )
        assertTrue(plugin.supportsTarget(TargetAddress.of("matrix", TargetKind.CHANNEL, "room")))
        assertFalse(plugin.supportsTarget(TargetAddress.of("kook", TargetKind.CHANNEL, "1")))
        assertFalse(plugin.supportsTarget(TargetAddress.of("qq", TargetKind.GROUP, "10001")))
    }

    @Test
    fun `message send should accept platform discovered from koishi`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val gateway = FakeGateway(
            MessageSinkRouteState.READY,
            accounts = listOf(runtimeAccount("matrix", "7", "Matrix Bot")),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = sendRequest(TargetAddress.of("matrix", TargetKind.CHANNEL, "room")),
            routeId = "koishi:matrix:7",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals("koishi:matrix:7", sent.receipts.single().sinkRouteId)
        assertEquals(1, gateway.sendCalls)
    }

    @Test
    fun `qq accounts from koishi should not be advertised as routes`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        plugin.setPrivate(
            "gateway",
            FakeGateway(
                MessageSinkRouteState.READY,
                accounts = listOf(
                    runtimeAccount("qq", "10001", "QQ Bot"),
                    runtimeAccount("discord", "42", "Discord Bot"),
                ),
            ),
        )
        plugin.setPrivate("running", true)

        val routes = plugin.listMessageSinkRoutes(null)
        assertEquals(listOf("koishi:discord:42"), routes.map { it.routeId })
        assertEquals(setOf(PlatformId.of("discord")), plugin.supportedTargetPlatforms)
    }

    @Test
    fun `message send should reject qq targets without sending`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val gateway = FakeGateway(MessageSinkRouteState.READY)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val target = TargetAddress.of("qq", TargetKind.GROUP, "10001")
        val result = plugin.sendMessage(
            request = sendRequest(target),
            routeId = "koishi:discord:42",
        )

        val failed = assertIs<MessageSendResult.Failed>(result)
        assertEquals("目标平台或类型不受 Koishi 网关支持", failed.reason)
        assertFalse(failed.retryable)
        assertEquals(0, gateway.sendCalls)
    }

    @Test
    fun `message send should return failed when gateway fails`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            sendOutcome = KoishiSendOutcome.Failed("Discord 拒绝", retryable = false),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = sendRequest(TargetAddress.of("discord", TargetKind.CHANNEL, "111")),
            routeId = "koishi:discord:42",
        )

        val failed = assertIs<MessageSendResult.Failed>(result)
        assertEquals("Discord 拒绝", failed.reason)
        assertFalse(failed.retryable)
    }

    @Test
    fun `message send should return sent when gateway accepts`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val gateway = FakeGateway(MessageSinkRouteState.READY)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)
        val target = TargetAddress.of("discord", TargetKind.CHANNEL, "111")

        val result = plugin.sendMessage(
            request = sendRequest(target),
            routeId = "koishi:discord:42",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals(listOf("sink-1"), sent.sinkMessageIds)
        assertEquals("koishi:discord:42", sent.receipts.single().sinkRouteId)
        assertEquals("42", sent.receipts.single().sinkAccountId)
        assertEquals(1, gateway.sendCalls)
    }

    @Test
    fun `message send should return uncertain on timeout`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            sendOutcome = KoishiSendOutcome.Uncertain("发送响应超时"),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)
        val target = TargetAddress.of("telegram", TargetKind.GROUP, "-100")

        val result = plugin.sendMessage(
            request = sendRequest(target),
            routeId = "koishi:telegram:42",
        )

        val uncertain = assertIs<MessageSendResult.Uncertain>(result)
        assertEquals("发送响应超时", uncertain.reason)
        assertEquals("koishi:telegram:42", uncertain.sinkRouteId)
    }

    @Test
    fun `resolve should keep hand-filled telegram ids`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        plugin.setPrivate("gateway", FakeGateway(MessageSinkRouteState.READY))
        plugin.setPrivate("running", true)

        val address = TargetAddress.of("telegram", TargetKind.GROUP, "-100123")
        val resolved = plugin.resolveMessageTarget(address)

        assertEquals("-100123", resolved?.address?.externalId)
        assertEquals(TargetKind.GROUP, resolved?.address?.kind)
    }

    @Test
    fun `incoming text message should publish asynchronously`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(scope) { request ->
            published.complete(request)
        }

        try {
            plugin.setPrivate("running", true)
            plugin.invokeIncoming(incomingMessage())

            val request = withTimeout(500) { published.await() }
            assertEquals("message-1", request.replyToMessageId)
            assertEquals("CHANNEL:111:message-1", request.sourceEventId)
            assertEquals("koishi-gateway:discord:42:CHANNEL:111:message-1", request.dedupeKey)
            assertEquals(request.dedupeKey!!.sha256Hex(), request.traceId)
            assertEquals("/db status", request.message.text)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `incoming command should be ignored after stop`() = runBlocking {
        val plugin = KoishiGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(scope) { request ->
            published.complete(request)
        }

        try {
            plugin.setPrivate("running", false)
            plugin.callPrivate("stopIncomingScope")
            plugin.invokeIncoming(incomingMessage())

            assertNull(withTimeoutOrNull(200) { published.await() })
        } finally {
            scope.cancel()
        }
    }

    private fun sendRequest(target: TargetAddress): MessageSendRequest {
        return MessageSendRequest(
            target = target,
            message = Message(
                id = "message-1",
                time = 1,
                targets = listOf(target),
                batches = listOf(MessageBatch(listOf(MessageContent.Text("ok")))),
            ),
        )
    }

    private fun incomingMessage(): KoishiIncomingMessage {
        return KoishiIncomingMessage(
            platformId = "discord",
            targetKind = TargetKind.CHANNEL,
            chatId = "111",
            senderId = "67890",
            text = "/db status",
            botAccountId = "42",
            messageId = "message-1",
        )
    }

    private fun KoishiGatewayPlugin.prepareIncomingTest(
        scope: CoroutineScope,
        publishIncoming: suspend (IncomingMessagePublishRequest) -> Unit,
    ) {
        setPrivate("pluginId", KOISHI_PLUGIN_ID)
        setPrivate("pluginScope", scope)
        setPrivate("incomingMessagePublisher", IncomingMessagePublisher { request -> publishIncoming(request) })
        callPrivate("startIncomingScope")
    }

    private fun KoishiGatewayPlugin.invokeIncoming(incoming: KoishiIncomingMessage) {
        val method = javaClass.getDeclaredMethod("onIncomingMessage", KoishiIncomingMessage::class.java)
        method.isAccessible = true
        method.invoke(this, incoming)
    }

    private fun Any.callPrivate(name: String) {
        val method = javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(this)
    }

    private class FakeGateway(
        state: MessageSinkRouteState,
        private val sendOutcome: KoishiSendOutcome = KoishiSendOutcome.Accepted("sink-1"),
        private val accounts: List<KoishiRuntimeAccount> = listOf(
            runtimeAccount("discord", "42", "Discord Bot", state),
            runtimeAccount("telegram", "42", "Telegram Bot", state),
        ),
    ) : KoishiGateway {
        var sendCalls: Int = 0

        override fun connect(onIncomingMessage: (KoishiIncomingMessage) -> Unit) {
        }

        override fun cachedAccounts(): List<KoishiRuntimeAccount> = accounts

        override suspend fun availableAccounts(): List<KoishiRuntimeAccount> = accounts

        override suspend fun sendMessage(
            accountId: String,
            target: TargetAddress,
            message: Message,
            replyToMessageId: String?,
        ): KoishiSendOutcome {
            sendCalls += 1
            return sendOutcome
        }

        override suspend fun recallMessage(accountId: String, target: TargetAddress, messageId: String) {
        }

        override suspend fun listTargets(accountId: String, kind: TargetKind?): List<KoishiTargetCandidate> = emptyList()

        override suspend fun close() {
        }
    }
}

private fun runtimeAccount(
    platform: String,
    accountId: String,
    name: String = accountId,
    state: MessageSinkRouteState = MessageSinkRouteState.READY,
): KoishiRuntimeAccount {
    return KoishiRuntimeAccount(
        accountId = accountId,
        platformId = PlatformId.of(platform),
        name = name,
        state = state,
    )
}

private fun Any.setPrivate(name: String, value: Any?) {
    val field = javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
