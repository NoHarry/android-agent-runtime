package cc.noharry.agent.runtime.thread

import cc.noharry.agent.NoticeDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun NoticeDetails.toJson(): JsonObject = buildJsonObject {
    attempt?.let { put("attempt", it) }
    delayMillis?.let { put("delayMillis", it) }
    roundsRemaining?.let { put("roundsRemaining", it) }
    toolCallsRemaining?.let { put("toolCallsRemaining", it) }
    beforeMessageCount?.let { put("beforeMessageCount", it) }
    afterMessageCount?.let { put("afterMessageCount", it) }
    workspaceId?.let { put("workspaceId", it) }
}

internal fun JsonObject?.noticeDetails(): NoticeDetails = NoticeDetails(
    attempt = this?.optionalLong("attempt")?.toInt(),
    delayMillis = this?.optionalLong("delayMillis"),
    roundsRemaining = this?.optionalLong("roundsRemaining")?.toInt(),
    toolCallsRemaining = this?.optionalLong("toolCallsRemaining")?.toInt(),
    beforeMessageCount = this?.optionalLong("beforeMessageCount")?.toInt(),
    afterMessageCount = this?.optionalLong("afterMessageCount")?.toInt(),
    workspaceId = this?.optionalText("workspaceId"),
)
