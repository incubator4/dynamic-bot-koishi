package com.incubator4.dynamic.koishi

import dbk.v1.Bot
import dbk.v1.BotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class DbkProtoTest {
    @Test
    fun `online bot maps to ready route`() {
        val account = bot(BotStatus.BOT_STATUS_READY).toRuntimeAccount()
        assertEquals(MessageSinkRouteState.READY, account?.state)
    }

    @Test
    fun `connecting bot is distinct from ready`() {
        val account = bot(BotStatus.BOT_STATUS_CONNECTING).toRuntimeAccount()
        assertNotEquals(MessageSinkRouteState.READY, account?.state)
        assertEquals(connectingRouteExpectation(), account?.state)
    }

    @Test
    fun `offline bot maps to unavailable route`() {
        val account = bot(BotStatus.BOT_STATUS_UNAVAILABLE).toRuntimeAccount()
        assertEquals(MessageSinkRouteState.UNAVAILABLE, account?.state)
    }

    private fun connectingRouteExpectation(): MessageSinkRouteState {
        return MessageSinkRouteState.entries.firstOrNull { it.name == "CONNECTING" }
            ?: MessageSinkRouteState.UNAVAILABLE
    }

    private fun bot(status: BotStatus): Bot = Bot(
        bot_key = "discord:42",
        platform = "discord",
        self_id = "42",
        name = "Discord Bot",
        status = status,
    )
}
