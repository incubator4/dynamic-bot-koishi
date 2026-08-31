package top.colter.dynamic.koishi

import dbk.v1.AudioSegment
import dbk.v1.ForwardNode
import dbk.v1.ForwardUnit
import dbk.v1.ImageSegment
import dbk.v1.MentionAllSegment
import dbk.v1.MentionSegment
import dbk.v1.NormalUnit
import dbk.v1.ReplySegment
import dbk.v1.Segment
import dbk.v1.SendParams
import dbk.v1.SendUnit
import dbk.v1.VideoSegment
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress

public object KoishiOutgoingMapper {
    public fun toSendParams(
        botKey: String,
        target: TargetAddress,
        message: Message,
        replyToMessageId: String?,
    ): SendParams {
        return SendParams(
            bot_key = botKey,
            target = target.toDbkTarget(),
            reply_to_message_id = replyToMessageId?.trim().orEmpty(),
            units = toSendUnits(message.batches, replyToMessageId),
        )
    }

    public fun toSendUnits(
        batches: List<MessageBatch>,
        replyToMessageId: String? = null,
    ): List<SendUnit> {
        val units = mutableListOf<SendUnit>()
        val normalBatches = mutableListOf<MessageBatch>()
        val replyTo = replyToMessageId?.trim()?.takeIf { it.isNotBlank() }

        fun flushNormal() {
            if (normalBatches.isEmpty()) return
            toNormalUnits(withReply(normalBatches, replyTo)).forEach { unit ->
                units += unit
            }
            normalBatches.clear()
        }

        batches.forEach { batch ->
            val current = mutableListOf<MessageContent>()

            fun flushBatch() {
                if (current.isNotEmpty()) {
                    normalBatches += MessageBatch(current.toList())
                    current.clear()
                }
            }

            batch.content.forEach { content ->
                when (content) {
                    is MessageContent.Forward -> {
                        flushBatch()
                        flushNormal()
                        units += SendUnit(body = SendUnit.BODY_FORWARD.create(content.toForwardUnit()))
                    }
                    else -> current += content
                }
            }
            flushBatch()
        }
        flushNormal()

        return units.ifEmpty {
            listOf(SendUnit(body = SendUnit.BODY_NORMAL.create(NormalUnit(segments = listOf(textSegment(EMPTY_MESSAGE_TEXT))))))
        }
    }

    private fun toNormalUnits(batches: List<MessageBatch>): List<SendUnit> {
        return batches
            .map { it.toSegments() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(listOf(textSegment(EMPTY_MESSAGE_TEXT))) }
            .map { segments -> SendUnit(body = SendUnit.BODY_NORMAL.create(NormalUnit(segments = segments))) }
    }

    private fun withReply(batches: List<MessageBatch>, replyToMessageId: String?): List<MessageBatch> {
        if (replyToMessageId.isNullOrBlank()) return batches
        if (batches.any { batch -> batch.content.any { it is MessageContent.Reply } }) return batches
        val first = batches.firstOrNull() ?: return listOf(MessageBatch(listOf(reply(replyToMessageId))))
        return listOf(first.copy(content = listOf(reply(replyToMessageId)) + first.content)) + batches.drop(1)
    }

    private fun MessageBatch.toSegments(): List<Segment> {
        val result = mutableListOf<Segment>()
        content.forEach { item ->
            when (item) {
                is MessageContent.Text -> result.addText(item.fallbackText)
                is MessageContent.Mention -> {
                    result.addText(item.fallbackText)
                    result += Segment(body = Segment.BODY_MENTION.create(MentionSegment(id = item.target.externalId)))
                }
                is MessageContent.MentionAll -> {
                    result.addText(item.fallbackText)
                    result += Segment(body = Segment.BODY_MENTION_ALL.create(MentionAllSegment()))
                }
                is MessageContent.Image -> {
                    result += Segment(
                        body = Segment.BODY_IMAGE.create(
                            ImageSegment(
                                uri = item.image.uri,
                                alt = item.altText ?: item.fallbackText,
                            ),
                        ),
                    )
                    result.addText(item.fallbackText)
                }
                is MessageContent.Video -> {
                    result += Segment(
                        body = Segment.BODY_VIDEO.create(
                            VideoSegment(
                                uri = item.video.uri,
                                alt = item.altText ?: item.fallbackText,
                            ),
                        ),
                    )
                    result.addText(item.fallbackText)
                }
                is MessageContent.Audio -> {
                    result += Segment(
                        body = Segment.BODY_AUDIO.create(
                            AudioSegment(
                                uri = item.audio.uri,
                                alt = item.altText ?: item.fallbackText,
                            ),
                        ),
                    )
                    result.addText(item.fallbackText)
                }
                is MessageContent.Reply -> {
                    result += Segment(body = Segment.BODY_REPLY.create(ReplySegment(message_id = item.messageId)))
                    result.addText(item.fallbackText)
                }
                is MessageContent.Forward -> result.addText(item.fallbackText)
            }
        }
        return result
    }

    private fun MessageContent.Forward.toForwardUnit(): ForwardUnit {
        return ForwardUnit(
            nodes = nodes.map { node ->
                ForwardNode(
                    sender_id = node.senderId,
                    sender_name = node.senderName,
                    sender_avatar = node.senderAvatar?.uri.orEmpty(),
                    time = node.time,
                    segments = node.batches.flatMapIndexed { index, batch ->
                        val segments = batch.toSegments()
                        if (index == 0 || segments.isEmpty()) {
                            segments
                        } else {
                            listOf(textSegment("\n")) + segments
                        }
                    },
                )
            },
        )
    }

    private fun MutableList<Segment>.addText(value: String) {
        if (value.isNotBlank() || value.any { it == '\n' || it == '\r' }) {
            add(textSegment(value))
        }
    }

    private fun reply(messageId: String): MessageContent.Reply = MessageContent.Reply(
        fallbackText = "",
        messageId = messageId,
    )

    private const val EMPTY_MESSAGE_TEXT: String = "（空消息）"
}
