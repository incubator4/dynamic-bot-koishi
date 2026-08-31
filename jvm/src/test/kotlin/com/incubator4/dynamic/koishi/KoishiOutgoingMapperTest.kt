package com.incubator4.dynamic.koishi

import dbk.v1.NormalUnit
import dbk.v1.Segment
import dbk.v1.SendUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class KoishiOutgoingMapperTest {
    @Test
    fun `mapper should send image uri and text`() {
        val target = TargetAddress.of("discord", TargetKind.CHANNEL, "111")
        val params = KoishiOutgoingMapper.toSendParams(
            botKey = "discord:42",
            target = target,
            message = demoMessage(
                listOf(
                    MessageContent.Image(
                        fallbackText = "",
                        image = MediaRef("https://example.com/a.png", MediaKind.IMAGE),
                    ),
                    MessageContent.Text("hello"),
                ),
            ),
            replyToMessageId = null,
        )

        assertEquals("discord:42", params.bot_key)
        assertEquals(dbk.v1.TargetKind.TARGET_KIND_CHANNEL, params.target?.kind)
        assertEquals("111", params.target?.id)
        val segments = assertIs<NormalUnit>(params.units.single().body?.valueIf(SendUnit.BODY_NORMAL)).segments
        assertEquals("https://example.com/a.png", segments[0].body?.valueIf(Segment.BODY_IMAGE)?.uri)
        assertEquals("hello", segments[1].body?.valueIf(Segment.BODY_TEXT)?.text)
    }

    @Test
    fun `mapper should prepend reply segment`() {
        val units = KoishiOutgoingMapper.toSendUnits(
            batches = listOf(MessageBatch(listOf(MessageContent.Text("pong")))),
            replyToMessageId = "message-1",
        )

        val segments = assertIs<NormalUnit>(units.single().body?.valueIf(SendUnit.BODY_NORMAL)).segments
        assertEquals("message-1", segments[0].body?.valueIf(Segment.BODY_REPLY)?.message_id)
        assertEquals("pong", segments[1].body?.valueIf(Segment.BODY_TEXT)?.text)
    }

    @Test
    fun `mapper should emit placeholder for empty message`() {
        val units = KoishiOutgoingMapper.toSendUnits(listOf(MessageBatch(emptyList())))
        val segments = assertIs<NormalUnit>(units.single().body?.valueIf(SendUnit.BODY_NORMAL)).segments
        assertEquals("（空消息）", segments.single().body?.valueIf(Segment.BODY_TEXT)?.text)
    }

    private fun demoMessage(content: List<MessageContent>): Message {
        val target = TargetAddress.of("discord", TargetKind.CHANNEL, "111")
        return Message(
            id = "message-1",
            time = 1,
            targets = listOf(target),
            batches = listOf(MessageBatch(content)),
        )
    }
}
