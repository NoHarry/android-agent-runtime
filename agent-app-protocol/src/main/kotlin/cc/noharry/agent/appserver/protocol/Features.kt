package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where client mode sends when nothing else is set: the relay on this device. */
const val DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT = "http://127.0.0.1:8766/a2a"

/** A question's phase while it waits, and after its task ended unanswered. */
const val QUESTION_PENDING = "pending"

const val QUESTION_EXPIRED = "expired"

/** How often a watch reads its source unless the plan says otherwise. */
const val DEFAULT_WATCH_POLL_MS = 30_000L

private const val WEEK_DAYS = 7L

private const val MINUTES_PER_HOUR = 60

/** Where a client-mode message was typed or injected; shown on the user turn. */
@Serializable
enum class A2aInputSource {
    CONSOLE,
    OVERLAY,
    ADB,
    ASR,
    /** An inbound request from a PC debug tool (`metadata.debugMirror=true`), mirrored read-only. */
    PC,
    /** An inbound request from any other A2A client, mirrored read-only. */
    EXTERNAL,
}

/** An A2A task's state as its agent reports it. */
@Serializable
enum class A2aTaskStatus {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    COMPLETED,
    CANCELLED,
    FAILED,
    /** The agent found it cannot proceed: the task is not done, and the agent said so rather than failing. */
    REJECTED,
    UNKNOWN,
}

/** Wire-only client projection: every item comes from what crossed A2A, never from local runtime events. */
@Serializable
sealed interface A2aClientItem {
    val id: Long

    @Serializable
    @SerialName("user")
    data class User(override val id: Long, val text: String, val source: A2aInputSource, val kind: Kind) : A2aClientItem {
        @Serializable
        enum class Kind { MESSAGE, STEER, ANSWER }
    }

    @Serializable
    @SerialName("status")
    data class Status(override val id: Long, val taskId: String, val text: String) : A2aClientItem
    @Serializable
    @SerialName("tool")
    data class Tool(
        override val id: Long,
        val taskId: String,
        val callId: String,
        val name: String,
        val phase: String,
        val startedAtEpochMillis: Long,
        val finishedAtEpochMillis: Long?,
    ) : A2aClientItem

    @Serializable
    @SerialName("speech")
    data class Speech(override val id: Long, val taskId: String, val text: String, val final: Boolean) : A2aClientItem
    @Serializable
    @SerialName("display")
    data class Display(override val id: Long, val taskId: String, val text: String) : A2aClientItem
    @Serializable
    @SerialName("question")
    data class Question(
        override val id: Long,
        val taskId: String,
        val questionId: String,
        val text: String,
        val options: List<String>,
        val phase: String,
    ) : A2aClientItem
    @Serializable
    @SerialName("terminal")
    data class Terminal(override val id: Long, val taskId: String, val state: A2aTaskStatus, val text: String, val elapsedMillis: Long?) : A2aClientItem

    @Serializable
    @SerialName("notice")
    data class Notice(override val id: Long, val text: String, val error: Boolean = false) : A2aClientItem
}

@Serializable
data class A2aClientState(
    val enabled: Boolean = false,
    /** Inbound tasks from other A2A clients are also spoken here (they are always shown); PC debug ones always are. */
    val speakExternal: Boolean = false,
    val endpoint: String = DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT,
    val speechReady: Boolean = false,
    val contextId: String? = null,
    val running: Boolean = false,
    val activeTaskId: String? = null,
    val lastTaskId: String? = null,
    val runStartedAtEpochMillis: Long? = null,
    /** Mirrored inbound tasks still running, by task ID, with their start time. */
    val mirrors: Map<String, Long> = emptyMap(),
    val lastOutcome: A2aOutcome? = null,
    val items: List<A2aClientItem> = emptyList(),
    val nextItemId: Long = 1,
    /** Another client's task arrived after the device's own last run, so a screen showing one thing shows the A2A tasks. */
    val inboundFocus: Boolean = false,
) {
    /** Questions of the active task, or when idle of the latest task; ended tasks answer late as a new message. */
    val questionCards: List<Pair<A2aClientItem.Question, QuestionAnswerMode>>
        get() {
            val taskId = activeTaskId ?: lastTaskId ?: return emptyList()
            val late = activeTaskId == null
            return items.filterIsInstance<A2aClientItem.Question>().filter { it.taskId == taskId }.map { question ->
                question to when {
                    question.phase == QUESTION_PENDING -> if (late) QuestionAnswerMode.LATE else QuestionAnswerMode.ACTIVE
                    question.phase == QUESTION_EXPIRED && late -> QuestionAnswerMode.LATE
                    else -> QuestionAnswerMode.NONE
                }
            }
        }

    fun question(questionId: String): A2aClientItem.Question? =
        items.lastOrNull { it is A2aClientItem.Question && it.questionId == questionId } as? A2aClientItem.Question
}

/** How the last task ended, and how long it ran (milliseconds; null when its start was not seen). */
@Serializable
data class A2aOutcome(val status: A2aTaskStatus, val elapsedMillis: Long? = null)

/** How a card may be answered: into the running run, as a new turn after the run ended, or not at all. */
@Serializable
enum class QuestionAnswerMode { ACTIVE, LATE, NONE }

/**
 * Where a followed delegation stands, so the person can see it before its result comes. The relay says
 * whether the bot has read the task, is reporting progress, or has not started 90 s after delivery.
 */
@Serializable
enum class DelegationStage(val settled: Boolean) {
    DELIVERED(false),
    STALLED(false),
    STARTED(false),
    WORKING(false),
    /** Settled; its report waits to be spoken (the device is busy, or the user is reading a card). */
    REPORTING(true),
    DONE(true),
    ASKING(true),
    FAILED(true),
    /** Settled: the agent found it cannot proceed and did not do it; not done, rather than failed. */
    REJECTED(true),
    CANCELLED(true),
    /** The device stopped following without an answer (gave up, or could not resume after a restart). */
    LOST(true),
}

/** One step of the remote agent's plan; [status] is done, doing or todo. */
@Serializable
data class DelegationStep(val title: String, val status: String)

/** A delegation as the console's 后台任务 and the overlay show it. */
@Serializable
data class DelegationView(
    val profileId: String,
    val agentLabel: String,
    val taskId: String,
    val request: String,
    val startedAtEpochMillis: Long,
    val stage: DelegationStage,
    val statusText: String = "",
    val steps: List<DelegationStep> = emptyList(),
    val updatedAtEpochMillis: Long = startedAtEpochMillis,
    /** The report's short line once settled, as the transcript has it (in the model's words). */
    val outcome: String? = null,
    /** The agent's own words in its report once settled, shown as they are; empty when it said nothing. */
    val said: String = "",
    /** Why it ended with no report of the agent's own (stopped here, lost after a restart, no answer in time). */
    val ended: Finding? = null,
)

/** When a plan comes due. */
@Serializable
sealed interface PlanTrigger {
    /** Once, at [atMillis]. */
    @Serializable
    @SerialName("once")
    data class Once(val atMillis: Long) : PlanTrigger

    /** At [minuteOfDay] (local time) on [days], ISO day numbers 1 (Monday) to 7 (Sunday). */
    @Serializable
    @SerialName("repeat")
    data class Repeat(val minuteOfDay: Int, val days: Set<Int>) : PlanTrigger

    /**
     * When [watch] matches a reading: a number over or under its threshold for [holdSeconds], a value the field becomes,
     * or each event; [repeat] fires again later.
     */
    @Serializable
    @SerialName("watch")
    data class Watch(val watch: PlanWatch, val holdSeconds: Int, val repeat: Boolean) : PlanTrigger
}

/**
 * A plan the person makes on the plans page ([FeatureRequests.PLAN_CREATE]): at [trigger], a reminder that says
 * [message], or, with [instruction], something the assistant then does.
 */
@Serializable
data class PlanDraft(val title: String, val message: String = "", val instruction: String? = null, val trigger: PlanTrigger)

/** How a plan ended: it did its one job, or its time passed while the device slept too long. */
@Serializable
enum class PlanEnd(val id: String) {
    DONE("done"),
    MISSED("missed"),
    ;

    companion object {
        fun byId(id: String?): PlanEnd? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One plan: [message] is said when it comes due (a reminder), or, with [instruction], the assistant does that as a turn
 * of its own, and [actions] are device controls the app runs itself, without the model (an automation).
 * [lastDueAtMillis] is the last occurrence of a repeat handled (fired or skipped), so none is handled
 * twice.
 */
@Serializable
data class Plan(
    val id: String,
    val title: String,
    val message: String,
    val instruction: String?,
    val trigger: PlanTrigger,
    val enabled: Boolean,
    val createdAtMillis: Long,
    val lastFiredAtMillis: Long? = null,
    val lastDueAtMillis: Long? = null,
    val firedCount: Int = 0,
    val end: PlanEnd? = null,
    val said: String? = null,
    val actions: List<PlanAction> = emptyList(),
) {
    /** Still waiting to come due: switched on and not ended. */
    val active: Boolean get() = enabled && end == null
}

/** A plan came due (or was missed), for the 计划 page's 最近触发: what came of it ([note]). */
@Serializable
data class PlanFiring(val planId: String, val title: String, val atMillis: Long, val note: Finding)

/** How a watch compares its field: a number above or below a value, equal or not to a value, containing words, or any change. */
@Serializable
enum class WatchOp(val id: String) {
    ABOVE("above"),
    BELOW("below"),
    EQUALS("equals"),
    NOT_EQUALS("not_equals"),
    CONTAINS("contains"),
    CHANGES("changes"),
    ;

    val numeric: Boolean get() = this == ABOVE || this == BELOW

    companion object {
        fun byId(id: String?): WatchOp? = entries.firstOrNull { it.id == id }
    }
}

/** When a matching reading fires: on entering the match (a state something changes into), or on each one (an event). */
@Serializable
enum class WatchMode(val id: String) {
    ENTER("enter"),
    EACH("each"),
    ;

    companion object {
        fun byId(id: String?): WatchMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a plan waits for, on whatever MCP servers are loaded (not a list built into the app): [server]'s resource [uri],
 * or, for a server that offers tools only, its read-only [tool] called with [arguments] (an open flight search: 「深圳飞
 * 成都 11 月 7 日的最低价」). Its [field] (a dot path in the reading, 「itineraries.0.price」「current.condition」) is
 * compared by [op] with [value], only in readings whose [where] fields match (one item of several). The model finds
 * resources with plan_manage `signals`, tools in the server's catalog, and reads either once with `inspect`; [text] is
 * its words for the rule (「最低价低于 800 元时」). [margin] is how far back past a threshold a number must go before a
 * repeating plan may fire again.
 */
@Serializable
data class PlanWatch(
    val server: String,
    val uri: String,
    val field: String,
    val op: WatchOp,
    val value: JsonPrimitive?,
    val text: String,
    val where: Map<String, JsonPrimitive> = emptyMap(),
    val mode: WatchMode = WatchMode.ENTER,
    val margin: Double = 0.0,
    val pollMillis: Long = DEFAULT_WATCH_POLL_MS,
    /** A read-only tool read instead of a resource; [uri] is then empty. */
    val tool: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
) {
    /** What the watch reads, as one key: the resource, or the tool with its arguments. */
    val source: String get() = tool?.let { "tool:$it $arguments" } ?: uri

    /** The source in words for people and logs: the uri, or the tool's name. */
    val sourceLabel: String get() = tool ?: uri
}

/** A control a plan runs itself when it comes due: a tool of a loaded MCP server, its arguments, and how it reads. */
@Serializable
data class PlanAction(val server: String, val tool: String, val arguments: JsonObject, val label: String)

/** Where one watched resource stands, for the 计划 page. */
@Serializable
enum class SubscriptionState {
    OFFLINE,
    PENDING,
    LIVE,
    /** The server answered a subscribe with an error: its owners read it on their own pace instead. */
    REFUSED,
    /** Read on the plan's own pace. */
    NO_PUSH,
}

/** The next time [this] comes due, or null for a condition and for a paused or ended plan. A past time is due now. */
fun Plan.nextDueMillis(now: Long, zone: ZoneId): Long? {
    if (!active) return null
    return when (val trigger = trigger) {
        is PlanTrigger.Once -> trigger.atMillis
        is PlanTrigger.Repeat -> nextRepeat(trigger, now, zone)
        is PlanTrigger.Watch -> null
    }
}

/** The first occurrence of [repeat] strictly after [after]. */
fun nextRepeat(repeat: PlanTrigger.Repeat, after: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
    return (0L..WEEK_DAYS).asSequence().map(today::plusDays)
        .filter { it.dayOfWeek.value in repeat.days }
        .map { repeatAt(repeat, it, zone) }
        .first { it > after }
}

/** [repeat]'s time on [day], in epoch milliseconds. */
fun repeatAt(repeat: PlanTrigger.Repeat, day: LocalDate, zone: ZoneId): Long =
    day.atTime(repeat.minuteOfDay / MINUTES_PER_HOUR, repeat.minuteOfDay % MINUTES_PER_HOUR).atZone(zone).toInstant().toEpochMilli()
