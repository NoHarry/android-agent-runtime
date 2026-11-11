package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.view.Offer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the reply surfaces show beside the text: the latest offer of each session (choices, a panel, cards; an A2A
 * client's under `a2a-<context>`), and the pictures drawn for A2A tasks, by task ID.
 */
@Serializable
@SerialName("offers")
data class OffersSection(
    val offers: Map<String, Offer> = emptyMap(),
    val pictures: Map<String, GeneratedPicture> = emptyMap(),
) : StateSection

/** A picture the image tool draws: [reference] is null while it is still being drawn; [prompt] is what was asked for. */
@Serializable
data class GeneratedPicture(val reference: String?, val prompt: String)

/** Where a subagent stands. */
@Serializable
enum class SubagentRunState {
    RUNNING,
    STALLED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    ;

    val finished: Boolean get() = this != RUNNING && this != STALLED

    /** As logs and the board write it: running, timed_out, ... */
    val wireName: String get() = name.lowercase()
}

/** One subagent as the card's lanes and the console's board show it. Times are epoch milliseconds; 0 until they happen. */
@Serializable
data class SubagentLane(
    val id: String,
    val label: String,
    val state: SubagentRunState,
    val startedAtEpochMillis: Long,
    val lastActivityEpochMillis: Long = 0,
    val finishedAtEpochMillis: Long = 0,
    val toolCalls: Int = 0,
    /** Its latest tool call, shortened. */
    val latest: String = "",
    /** Its answer when completed. */
    val result: String = "",
    val error: String = "",
    val parentSessionId: String = "",
    /** Its own session, where its event log is. */
    val sessionId: String = "",
    val task: String = "",
    val modelCalls: Int = 0,
    /** Input tokens of its model calls, cache reads included. */
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** Output tokens, reasoning included. */
    val outputTokens: Long = 0,
    /** The subagent type it runs as; blank for a general one. */
    val agentType: String = "",
)

/**
 * The subagents of each session ([board], by parent session), and those whose late result is on its way to the
 * conversation ([deliveries], by "parent/child": [REPORT_QUEUED] until the report's turn starts, then when it started).
 */
@Serializable
@SerialName("subagents")
data class SubagentsSection(
    val board: Map<String, List<SubagentLane>> = emptyMap(),
    val deliveries: Map<String, Long> = emptyMap(),
) : StateSection

/** [SubagentsSection.deliveries]: the report waits for its turn. */
const val REPORT_QUEUED = 0L

/** The key [SubagentsSection.deliveries] uses for a subagent. */
fun SubagentLane.deliveryKey(): String = "$parentSessionId/$id"

/** What a plan coming due does: run controls of its own, hand a task to the assistant, or remind. */
@Serializable
enum class PlanKind {
    @SerialName("auto")
    AUTO,

    @SerialName("task")
    TASK,

    @SerialName("reminder")
    REMINDER,
}

/** One plan coming due, as its popup shows it. */
@Serializable
data class PlanNoticeEntry(
    val planId: String,
    val kind: PlanKind,
    val title: String,
    /** The task handed to the assistant, as the plan says it. */
    val handed: String? = null,
    /** What was said out loud (a reminder's words). */
    val said: String? = null,
    /** The names of the controls it runs. */
    val actions: List<String> = emptyList(),
    /** How its controls went, once they are done. */
    val outcome: Finding? = null,
)
