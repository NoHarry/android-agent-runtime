package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.Serializable

/** A thread's event log as the runtime reports it: complete, degraded, unavailable, volatile, or unknown. */
@Serializable
enum class EventLogHealthStatus {
    COMPLETE,
    DEGRADED,
    UNAVAILABLE,
    VOLATILE,
    UNKNOWN,
}

/** Whether the event log outlives the process. */
@Serializable
enum class EventLogDurability {
    DURABLE,
    VOLATILE,
    UNKNOWN,
}

@JvmInline
@Serializable
value class TrajectoryRecordId(val value: String)

/** Where a record's identity came from. A record can carry more than one contributing source. */
@Serializable
enum class TrajectorySource {
    EVENT_LOG,
    SESSION_TURN,
    RUN_EVIDENCE,
    SYNTHETIC_BOUNDARY,
}

/** `SYSTEM` rows appear in the ledger and as full-height markers; visible lanes stay Input/Model/Tools. */
@Serializable
enum class TrajectoryLane {
    INPUT,
    MODEL,
    TOOLS,
    SYSTEM,
}

@Serializable
enum class TrajectoryKind {
    RUN_START,
    INPUT,
    THINKING,
    MODEL_MESSAGE,
    MODEL_CALL,
    MODEL_TOOL,
    TOOL_START,
    TOOL_FINISH,
    USAGE,
    CONFIRMATION,
    RETRY,
    COMPACTION,
    NETWORK,
    SEARCH,
    EXEC_OUTPUT,
    WORKSPACE,
    STATUS,
    RESULT,
    RUN_END,
    GAP,
    LOAD_ERROR,
}

/** How trustworthy a record's timestamp is; live arrival time is never Runtime occurrence time. */
@Serializable
enum class TrajectoryTimeQuality {
    PERSISTED_RECORDED,
    LIVE_ARRIVAL,
    SUMMARY_RANGE,
    UNKNOWN,
}

/**
 * Availability of rich detail (payload/result/schema), tracked separately from EventLog health:
 * a COMPLETE metadata log still has no raw payload, and missing rich detail is not corruption.
 */
@Serializable
enum class TrajectoryRichAvailability {
    AVAILABLE,
    PARTIAL,
    REDACTED,
    HASH_ONLY,
    UNAVAILABLE,
}

@Serializable
data class TrajectoryCorrelation(
    val toolCallId: String? = null,
    val modelCallId: String? = null,
    val lifecyclePhase: String? = null,
    val localOrdinal: Long? = null,
)

/** Display-only projection; never serialized back into the EventLog. */
@Serializable
data class TrajectoryRichDetails(
    val inputPreview: String? = null,
    val payloadPreview: String? = null,
    val resultPreview: String? = null,
    val schemaPreview: String? = null,
    val requestText: String? = null,
    val thinkingText: String? = null,
    val outputText: String? = null,
    val usageText: String? = null,
    val timing: Map<String, Long> = emptyMap(),
    /** A question the run asked the person, when the record is one. */
    val question: TrajectoryQuestion? = null,
    /** A model call's request, token use or end, when the record is one: what context use is worked out from. */
    val modelCall: TrajectoryModelCall? = null,
)

/**
 * A record's event in full, which an inspector asks for when it shows it ([SettingsRequests.TRAJECTORY_RAW]): a run's
 * every prompt in full would otherwise ride each change of the trajectory.
 */
@Serializable
data class TrajectoryRawParams(val sessionId: String, val eventId: String)

/** The event as recorded; null when the server does not hold it (any more). */
@Serializable
data class TrajectoryRaw(val text: String? = null)

/** A question the run asked the person, as its event recorded it; [state] is PENDING until answered. */
@Serializable
data class TrajectoryQuestion(
    val questionId: String,
    val kind: String,
    val text: String,
    val options: List<String> = emptyList(),
    val state: String,
)

@Serializable
enum class ModelCallPhase {
    REQUEST,
    USAGE,
    FINISHED,
}

/** One step of a model call: its request (with [modelRef]), its input tokens, or its end (with [source]). */
@Serializable
data class TrajectoryModelCall(
    val phase: ModelCallPhase,
    val modelRef: String = "",
    val inputTokens: Long = 0,
    val source: String = "",
)

@Serializable
data class TrajectoryRecord(
    val id: TrajectoryRecordId,
    val sessionId: String,
    val runId: String,
    val requestId: String? = null,
    val eventId: String? = null,
    val sequence: Long? = null,
    val occurredAtEpochMillis: Long? = null,
    val timeQuality: TrajectoryTimeQuality = TrajectoryTimeQuality.UNKNOWN,
    val kind: TrajectoryKind,
    val lane: TrajectoryLane,
    val status: String? = null,
    val title: String,
    val summary: String = "",
    val correlation: TrajectoryCorrelation = TrajectoryCorrelation(),
    val safeMetadata: Map<String, String> = emptyMap(),
    val contentHashes: Map<String, String> = emptyMap(),
    val sourceEventSequences: List<Long> = emptyList(),
    val sources: Set<TrajectorySource> = emptySet(),
    val rich: TrajectoryRichDetails? = null,
    val richAvailability: TrajectoryRichAvailability = TrajectoryRichAvailability.UNAVAILABLE,
    val isLive: Boolean = false,
)

@Serializable
data class TrajectoryHealth(
    val eventLogStatus: EventLogHealthStatus = EventLogHealthStatus.UNKNOWN,
    val durability: EventLogDurability = EventLogDurability.UNKNOWN,
    val attemptedCount: Int = 0,
    val appendedCount: Int = 0,
    val failedCount: Int = 0,
    val lastAcceptedSequence: Long? = null,
    val lastPersistedSequence: Long? = null,
    val hasSequenceGap: Boolean = false,
    val hasLiveOnlyRecords: Boolean = false,
    val richAvailability: TrajectoryRichAvailability = TrajectoryRichAvailability.UNAVAILABLE,
    val safeReasonCode: String? = null,
) {
    /** Bounded badge text; health must never be conveyed by colour alone. */
    val badgeLabel: String
        get() = when (eventLogStatus) {
            EventLogHealthStatus.COMPLETE -> "Complete"
            EventLogHealthStatus.DEGRADED -> "Degraded"
            EventLogHealthStatus.UNAVAILABLE -> "Unavailable"
            EventLogHealthStatus.VOLATILE -> "Volatile"
            EventLogHealthStatus.UNKNOWN -> "Unknown"
        }
}

@Serializable
data class TrajectoryRunGroup(
    val sessionId: String,
    val runId: String,
    val requestId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val lifecycleStatus: String = OUTCOME_UNKNOWN,
    val completionOutcome: String? = null,
    val isActive: Boolean = false,
    val recordsLoaded: Boolean = false,
    /** True when the durable read failed; the group is retryable, not merely unexpanded. */
    val loadFailed: Boolean = false,
    val records: List<TrajectoryRecord> = emptyList(),
    val health: TrajectoryHealth = TrajectoryHealth(),
) {
    val toolCallCount: Int
        get() = records.mapNotNull { it.correlation.toolCallId }.toSet().size
}

@Serializable
data class TrajectorySnapshot(
    val sessionId: String,
    val runGroups: List<TrajectoryRunGroup> = emptyList(),
    val visibleRunCount: Int = 0,
    val totalKnownRunCount: Int = 0,
    val hasOlderRuns: Boolean = false,
    val loadingRunIds: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    /**
     * live id -> durable id for rows reconciliation has replaced. Published so selection and
     * scroll anchors can follow a row whose identity changed under them.
     */
    val recordAliases: Map<TrajectoryRecordId, TrajectoryRecordId> = emptyMap(),
    val revision: Long = 0L,
) {
    val isEmpty: Boolean get() = runGroups.isEmpty()

    /** Follows an alias chain so a selection made before reconciliation still resolves. */
    fun resolveRecordAlias(id: TrajectoryRecordId): TrajectoryRecordId {
        var current = id
        var hops = 0
        while (hops < MAX_ALIAS_HOPS) {
            val next = recordAliases[current] ?: return current
            if (next == current) return current
            current = next
            hops += 1
        }
        return current
    }

    private companion object {
        /** Aliases are one hop in practice; the bound only guards against a malformed cycle. */
        const val MAX_ALIAS_HOPS = 4
    }
}
