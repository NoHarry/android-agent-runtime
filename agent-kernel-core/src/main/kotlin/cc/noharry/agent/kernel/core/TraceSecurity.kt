package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad

data class TraceVisibilityConfig(
    val buildType: RuntimeBuildType = RuntimeBuildType.DEBUG,
    val exposeRawTraceWorkspace: Boolean = false,
    val exposeModelTraceSummary: Boolean = false,
    val allowDebugRawTraceAccess: Boolean = false,
) {
    fun validate() {
        if (buildType == RuntimeBuildType.RELEASE && exposeRawTraceWorkspace) {
            throw IllegalArgumentException(RELEASE_RAW_TRACE_ERROR)
        }
        if (buildType == RuntimeBuildType.RELEASE && allowDebugRawTraceAccess) {
            throw IllegalArgumentException(RELEASE_DEBUG_RAW_TRACE_ERROR)
        }
    }

    fun debugRawTraceAccessDecision(): DebugRawTraceAccessDecision =
        when {
            buildType == RuntimeBuildType.RELEASE -> DebugRawTraceAccessDecision.Denied(
                RELEASE_DEBUG_RAW_TRACE_ERROR,
            )
            !allowDebugRawTraceAccess -> DebugRawTraceAccessDecision.Denied(
                DEBUG_RAW_TRACE_NOT_ENABLED,
            )
            else -> DebugRawTraceAccessDecision.Allowed
        }
}

enum class RuntimeBuildType {
    DEBUG,
    RELEASE,
}

sealed interface DebugRawTraceAccessDecision {
    data object Allowed : DebugRawTraceAccessDecision

    data class Denied(val reason: String) : DebugRawTraceAccessDecision
}

interface TraceRedactor {
    fun redact(value: String): String
}

object DefaultTraceRedactor : TraceRedactor {
    override fun redact(value: String): String =
        LOCATION_PATTERN.replace(
            PHONE_PATTERN.replace(SECRET_PATTERN.replace(value, REDACTED_SECRET)) {
                if (it.value.isDateLike()) it.value else REDACTED_PHONE
            },
            REDACTED_LOCATION,
        )
}

data class RedactedTraceSummary(
    val runId: String,
    val status: String?,
    val phase: String?,
    val eventNames: List<String>,
    val readPaths: List<String>,
    val writeArtifacts: List<String>,
    val attemptedTools: List<String>,
    val toolRegistryWarnings: List<String>,
    val toolRegistryFailures: List<String>,
    val planSummaries: List<String>,
    val blockerSummaries: List<String>,
    val handoffSummary: String?,
    val openConfusionSummary: String?,
    val resultSummary: String?,
)

class TraceSummaryBuilder(
    private val redactor: TraceRedactor = DefaultTraceRedactor,
) {
    fun build(
        runId: String,
        envelope: RuntimeRunEnvelope?,
        workpad: RuntimeWorkpad?,
        handoff: RuntimeHandoff?,
        openConfusion: OpenConfusion?,
        result: RuntimeResult?,
        traceEvents: List<RuntimeTraceEvent>,
    ): RedactedTraceSummary = RedactedTraceSummary(
        runId = runId,
        status = envelope?.status?.name,
        phase = envelope?.phase?.name,
        eventNames = traceEvents.map { it.eventName },
        readPaths = workpad?.readPaths.orEmpty(),
        writeArtifacts = workpad?.writeArtifacts.orEmpty().map(redactor::redact),
        attemptedTools = workpad?.attemptedTools.orEmpty(),
        toolRegistryWarnings = workpad?.toolRegistryWarnings.orEmpty().map(redactor::redact),
        toolRegistryFailures = workpad?.toolRegistryFailures.orEmpty().map(redactor::redact),
        planSummaries = workpad?.planSummaries.orEmpty().map(redactor::redact),
        blockerSummaries = workpad?.blockers.orEmpty().map { blocker ->
            "${blocker.code}: ${redactor.redact(blocker.message)}"
        },
        handoffSummary = handoff?.let { redactor.redact((listOf(it.summary) + it.nextActions).joinToString(" ")) },
        openConfusionSummary = openConfusion?.let { redactor.redact(it.question) },
        resultSummary = result?.let { redactor.redact(it.response) },
    )
}

val RELEASE_RAW_TRACE_ERROR =
    RuntimeErrorCode.RAW_TRACE_WORKSPACE_FORBIDDEN_IN_RELEASE.wireCode
val RELEASE_DEBUG_RAW_TRACE_ERROR =
    RuntimeErrorCode.DEBUG_RAW_TRACE_FORBIDDEN_IN_RELEASE.wireCode
val DEBUG_RAW_TRACE_NOT_ENABLED =
    RuntimeErrorCode.DEBUG_RAW_TRACE_ACCESS_NOT_ENABLED.wireCode

private const val REDACTED_SECRET = "[REDACTED_SECRET]"
private const val REDACTED_PHONE = "[REDACTED_PHONE]"
private const val REDACTED_LOCATION = "[REDACTED_LOCATION]"

private val SECRET_PATTERN = Regex(
    pattern = """(?i)\b(?:sk-[A-Za-z0-9_-]{8,}|token\s*[:=]\s*[\w.-]+|secret\s*[:=]\s*[\w.-]+)""",
)
private val PHONE_PATTERN = Regex("""(?<!\d)(?:\+?\d[\d -]{8,}\d)(?!\d)""")

/**
 * Dates read like numbers to [PHONE_PATTERN]: "2026-10-18", "10-18 10-19", "2026-10-17 2026-10-18". A match whose
 * groups are all years (19xx/20xx) or month/day numbers (up to 31) is a date, not a phone number, and stays (owner
 * 2026-10-17: a subagent's "时间窗：2026-09-18 至 2026-10-18" reached the card as [REDACTED_PHONE]). A number in pairs
 * ("06 12 34 56 78") has pairs no month or day reaches, so it is still hidden.
 */
private fun String.isDateLike(): Boolean =
    split(' ', '-').filter(String::isNotEmpty).all { group ->
        (group.length <= 2 && group.all(Char::isDigit) && group.toInt() <= MAX_DAY_OF_MONTH) ||
            (group.length == 4 && (group.startsWith("19") || group.startsWith("20")))
    }

private const val MAX_DAY_OF_MONTH = 31
private val LOCATION_PATTERN = Regex(
    pattern = """(?i)\b(?:lat(?:itude)?|lon(?:gitude)?)\s*[:=]\s*-?\d+(?:\.\d+)?""",
)
