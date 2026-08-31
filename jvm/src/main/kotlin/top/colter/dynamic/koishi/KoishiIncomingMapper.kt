package top.colter.dynamic.koishi

import dbk.v1.IncomingMessage as DbkIncomingMessage
import dbk.v1.Segment
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageReference
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress

public object KoishiIncomingMapper {
    public fun fromProto(incoming: DbkIncomingMessage): KoishiIncomingMessage? {
        val parsedKey = parseBotKey(incoming.bot_key)
        val platform = incoming.platform.trim().ifBlank { parsedKey?.first }.orEmpty()
        val accountId = parsedKey?.second
        val target = incoming.target ?: return null
        val kind = target.kind.toCore() ?: return null
        val chatId = target.id.trim()
        val senderId = incoming.sender_id.trim()
        if (platform.isBlank() || chatId.isBlank() || senderId.isBlank()) return null

        val segments = incoming.segments.map { it.toIncomingSegment() }.toMutableList()
        val replyId = incoming.reply_to_message_id.trim()
        if (replyId.isNotBlank() && segments.none { it is IncomingMessageSegment.Reply }) {
            segments.add(0, IncomingMessageSegment.Reply(messageId = replyId))
        }

        return KoishiIncomingMessage(
            platformId = platform,
            targetKind = kind,
            chatId = chatId,
            senderId = senderId,
            text = incoming.text,
            botAccountId = accountId,
            messageId = incoming.message_id,
            timestamp = incoming.timestamp,
            segments = segments,
            rawFormat = DBK_RAW_FORMAT,
            rawPayload = incoming.raw,
            mentionedAccountIds = incoming.mentions.filter { it.isNotBlank() }.toSet(),
        )
    }

    public fun toIncomingMessage(incoming: KoishiIncomingMessage): IncomingMessage {
        val platformId = PlatformId.of(incoming.platformId)
        return IncomingMessage(
            platformId = platformId,
            target = TargetAddress.of(
                platformId = platformId.value,
                kind = incoming.targetKind,
                externalId = incoming.chatId,
                accountId = incoming.botAccountId,
            ),
            senderId = incoming.senderId,
            botAccountId = incoming.botAccountId,
            messageId = incoming.messageId,
            replyTo = incoming.replyToReference(),
            timestamp = incoming.timestamp,
            text = incoming.text,
            segments = incoming.segments,
            rawFormat = incoming.rawFormat,
            rawPayload = incoming.rawPayload,
            mentions = incoming.mentionedAccountIds,
        )
    }

    private fun KoishiIncomingMessage.replyToReference(): IncomingMessageReference? {
        return segments
            .asSequence()
            .filterIsInstance<IncomingMessageSegment.Reply>()
            .mapNotNull { segment ->
                segment.messageId.trim().takeIf { it.isNotBlank() }?.let { messageId ->
                    IncomingMessageReference(
                        messageId = messageId,
                        rawPayload = segment.rawPayload,
                    )
                }
            }
            .firstOrNull()
    }

    private fun Segment.toIncomingSegment(): IncomingMessageSegment {
        val body = body ?: return IncomingMessageSegment.Unknown(segmentType = "empty")
        body.valueIf(Segment.BODY_TEXT)?.let { return IncomingMessageSegment.Text(text = it.text) }
        body.valueIf(Segment.BODY_IMAGE)?.let { segment ->
            return IncomingMessageSegment.Image(
                file = segment.file_,
                url = segment.uri.takeIf { it.isNotBlank() },
            )
        }
        body.valueIf(Segment.BODY_VIDEO)?.let { segment ->
            return IncomingMessageSegment.Video(
                file = segment.file_,
                url = segment.uri.takeIf { it.isNotBlank() },
            )
        }
        body.valueIf(Segment.BODY_AUDIO)?.let { segment ->
            return IncomingMessageSegment.Audio(
                file = segment.file_,
                url = segment.uri.takeIf { it.isNotBlank() },
            )
        }
        body.valueIf(Segment.BODY_MENTION)?.let { segment ->
            return IncomingMessageSegment.Mention(targetId = segment.id, all = false)
        }
        body.valueIf(Segment.BODY_MENTION_ALL)?.let {
            return IncomingMessageSegment.Mention(targetId = "", all = true)
        }
        body.valueIf(Segment.BODY_REPLY)?.let { segment ->
            val messageId = segment.message_id.trim()
            if (messageId.isBlank()) {
                return IncomingMessageSegment.Unknown(segmentType = "reply")
            }
            return IncomingMessageSegment.Reply(messageId = messageId)
        }
        body.valueIf(Segment.BODY_LINK)?.let { segment ->
            if (segment.url.isBlank()) {
                return IncomingMessageSegment.Unknown(segmentType = "link")
            }
            return IncomingMessageSegment.Link(
                url = segment.url,
                title = segment.title.takeIf { it.isNotBlank() },
            )
        }
        body.valueIf(Segment.BODY_UNKNOWN)?.let { segment ->
            return IncomingMessageSegment.Unknown(
                segmentType = segment.type,
                rawPayload = segment.raw,
            )
        }
        return IncomingMessageSegment.Unknown(segmentType = body.key.declaredName)
    }

    private const val DBK_RAW_FORMAT: String = "dbk-v1"
}
