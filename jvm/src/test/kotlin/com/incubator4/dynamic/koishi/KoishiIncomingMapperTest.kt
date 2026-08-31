package com.incubator4.dynamic.koishi

import dbk.v1.IncomingMessage as DbkIncomingMessage
import dbk.v1.ReplySegment
import dbk.v1.Segment
import dbk.v1.Target
import dbk.v1.TextSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetKind

class KoishiIncomingMapperTest {
    @Test
    fun `mapper should copy platform target and reply reference`() {
        val incoming = KoishiIncomingMessage(
            platformId = "discord",
            targetKind = TargetKind.CHANNEL,
            chatId = "111",
            senderId = "222",
            text = "hello",
            botAccountId = "42",
            messageId = "msg-1",
            timestamp = 10,
            segments = listOf(IncomingMessageSegment.Reply(messageId = "parent-1")),
            rawFormat = "dbk-v1",
            rawPayload = "{}",
            mentionedAccountIds = setOf("42"),
        )

        val mapped = KoishiIncomingMapper.toIncomingMessage(incoming)

        assertEquals("discord", mapped.platformId.value)
        assertEquals(TargetKind.CHANNEL, mapped.target.kind)
        assertEquals("111", mapped.target.externalId)
        assertEquals("42", mapped.target.accountId)
        assertEquals("222", mapped.senderId)
        assertEquals("msg-1", mapped.messageId)
        assertEquals("parent-1", mapped.replyTo?.messageId)
        assertEquals(setOf("42"), mapped.mentions)
    }

    @Test
    fun `fromProto should map generated incoming message`() {
        val proto = DbkIncomingMessage(
            bot_key = "discord:42",
            platform = "discord",
            target = Target(kind = dbk.v1.TargetKind.TARGET_KIND_CHANNEL, id = "111"),
            sender_id = "222",
            message_id = "msg-1",
            timestamp = 10,
            text = "/db status",
            reply_to_message_id = "parent-1",
            mentions = listOf("42"),
            segments = listOf(
                Segment(body = Segment.BODY_TEXT.create(TextSegment(text = "/db status"))),
                Segment(body = Segment.BODY_REPLY.create(ReplySegment(message_id = "parent-1"))),
            ),
            raw = "{}",
        )

        val mapped = KoishiIncomingMapper.fromProto(proto)!!
        val core = KoishiIncomingMapper.toIncomingMessage(mapped)

        assertEquals("discord", mapped.platformId)
        assertEquals("42", mapped.botAccountId)
        assertEquals("/db status", core.text)
        assertEquals("parent-1", core.replyTo?.messageId)
        assertEquals(setOf("42"), core.mentions)
    }
}
