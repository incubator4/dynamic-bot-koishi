package com.incubator4.dynamic.koishi

import com.squareup.wire.OneOf
import dbk.v1.Bot
import dbk.v1.BotStatus
import dbk.v1.Segment
import dbk.v1.Target
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import dbk.v1.TargetKind as DbkTargetKind

internal fun TargetKind.toDbk(): DbkTargetKind = when (this) {
    TargetKind.USER -> DbkTargetKind.TARGET_KIND_USER
    TargetKind.GROUP -> DbkTargetKind.TARGET_KIND_GROUP
    TargetKind.CHANNEL -> DbkTargetKind.TARGET_KIND_CHANNEL
    TargetKind.THREAD -> DbkTargetKind.TARGET_KIND_THREAD
    TargetKind.WEBHOOK, TargetKind.OTHER -> DbkTargetKind.TARGET_KIND_UNSPECIFIED
}

internal fun DbkTargetKind.toCore(): TargetKind? = when (this) {
    DbkTargetKind.TARGET_KIND_USER -> TargetKind.USER
    DbkTargetKind.TARGET_KIND_GROUP -> TargetKind.GROUP
    DbkTargetKind.TARGET_KIND_CHANNEL -> TargetKind.CHANNEL
    DbkTargetKind.TARGET_KIND_THREAD -> TargetKind.THREAD
    DbkTargetKind.TARGET_KIND_UNSPECIFIED -> null
}

internal fun TargetAddress.toDbkTarget(): Target = Target(
    kind = kind.toDbk(),
    id = externalId,
)

internal fun Bot.toRuntimeAccount(): KoishiRuntimeAccount? {
    val selfId = self_id.trim().ifBlank { parseBotKey(bot_key)?.second }.orEmpty()
    val platform = platform.trim().ifBlank { parseBotKey(bot_key)?.first }.orEmpty()
    if (selfId.isBlank() || platform.isBlank()) return null
    return KoishiRuntimeAccount(
        accountId = selfId,
        platformId = PlatformId.of(platform),
        name = name.trim().ifBlank { selfId },
        state = status.toRouteState(),
        features = features.toSet(),
        avatar = avatar,
        botKey = bot_key.trim().ifBlank { "$platform:$selfId" },
    )
}

internal fun KoishiRuntimeAccount.targetKinds(): Set<TargetKind> {
    val kinds = mutableSetOf<TargetKind>()
    if (DbkBotFeatures.TARGET_USER in features) kinds += TargetKind.USER
    if (DbkBotFeatures.TARGET_GROUP in features) kinds += TargetKind.GROUP
    if (DbkBotFeatures.TARGET_CHANNEL in features) kinds += TargetKind.CHANNEL
    if (DbkBotFeatures.TARGET_THREAD in features) kinds += TargetKind.THREAD
    return kinds
}

internal fun BotStatus.toRouteState(): MessageSinkRouteState = when (this) {
    BotStatus.BOT_STATUS_READY -> MessageSinkRouteState.READY
    BotStatus.BOT_STATUS_CONNECTING -> connectingRouteState()
    BotStatus.BOT_STATUS_UNAVAILABLE,
    BotStatus.BOT_STATUS_UNSPECIFIED,
    -> MessageSinkRouteState.UNAVAILABLE
}

private fun connectingRouteState(): MessageSinkRouteState {
    return MessageSinkRouteState.entries.firstOrNull { it.name == "CONNECTING" }
        ?: MessageSinkRouteState.UNAVAILABLE
}

internal fun parseBotKey(botKey: String): Pair<String, String>? {
    val value = botKey.trim()
    val separator = value.indexOf(':')
    if (separator <= 0 || separator == value.lastIndex) return null
    val platform = value.take(separator).trim()
    val selfId = value.substring(separator + 1).trim()
    if (platform.isBlank() || selfId.isBlank()) return null
    return platform to selfId
}

internal fun botKeyOf(platformId: PlatformId, accountId: String): String {
    return "${platformId.value}:$accountId"
}

@Suppress("UNCHECKED_CAST")
internal fun <T> OneOf<*, *>.valueIf(key: OneOf.Key<T>): T? {
    return if (this.key == key) value as T else null
}

internal fun textSegment(text: String): Segment {
    return Segment(body = Segment.BODY_TEXT.create(dbk.v1.TextSegment(text = text)))
}
