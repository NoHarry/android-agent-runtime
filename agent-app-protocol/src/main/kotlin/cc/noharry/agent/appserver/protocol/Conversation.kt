package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the wire protocol: adding fields or methods keeps it, removing or changing one raises it. */
const val PROTOCOL_VERSION = 1

/** What a message line in the conversation is. The person's words are a [ConversationItem.TurnHeader], not a role. */
@Serializable
enum class TranscriptRole {
    ASSISTANT,
    THINKING,
    RUNTIME,
    STATUS,
    ERROR,

    /** An app-level event worth a visible line, e.g. 连接应用 connected or disconnected; unlike STATUS it is shown. */
    NOTICE,
}

@Serializable
enum class ToolCallStatus {
    RUNNING,
    DONE,
    FAILED,
    UNKNOWN,
}

/**
 * How the server answers a call that needs a person's yes (a tool that acts, a web page's site): ask, or decide by
 * itself. The one a client sets is the person's and is kept across restarts. A call a plugin's tool makes that acts
 * asks in every mode but [DENY_ALL] (its approval's `alwaysAsk`). [ALLOW_ALL_SESSION] keeps its old name on the wire.
 */
@Serializable
enum class ConfirmationMode(
    val label: String,
    val shortLabel: String,
    val warning: Boolean = false,
) {
    ASK_EVERY_TIME("Ask every time", "Ask"),
    ALLOW_CONFIRMED_DOMAINS("Allow confirmed domains", "Domains"),
    ALLOW_ALL_SESSION("Allow all", "Allow all", warning = true),
    DENY_ALL("Deny all", "Deny all"),
}

@Serializable
enum class ConfirmationStatus {
    PENDING,
    APPROVED,
    DENIED,
    AUTO_APPROVED,
    AUTO_DENIED,
    CONSUMED,
    EXPIRED,
    FAILED,
}

/**
 * One line of a thread, as the server last saw it. Items are snapshots: the server sends a changed item whole
 * (`item/updated`), or only the text added to its end (`item/delta`). How an item is shown, folded or open, is the
 * client's own and never on the wire.
 */
@Serializable
sealed interface ConversationItem {
    val id: Long

    /** The person's words or an external turn's label; [USER_STEERING_LABEL] stays inside the current turn. */
    @Serializable
    @SerialName("userMessage")
    data class TurnHeader(
        override val id: Long,
        val label: String,
        val preview: String,
        val fullText: String,
    ) : ConversationItem

    /** Text in the turn: the answer, thinking, a status or an error line; [final] marks the run's answer. */
    @Serializable
    @SerialName("message")
    data class MessageItem(
        override val id: Long,
        val role: TranscriptRole,
        val content: String,
        val final: Boolean = false,
    ) : ConversationItem

    /** One tool call, from its start to its result. Times are epoch milliseconds. */
    @Serializable
    @SerialName("toolCall")
    data class ToolCallItem(
        override val id: Long,
        val toolCallId: String = "",
        val toolName: String,
        val argumentsJson: String? = null,
        val resultJson: String? = null,
        val status: ToolCallStatus,
        val startedAt: Long,
        val finishedAt: Long? = null,
    ) : ConversationItem

    /**
     * A tool waiting for, or past, a person's yes or no; the server also asks for it with `approval/request`. With
     * [alwaysAsk] the call asks every time whatever the [mode] (a plugin's or package's tool that acts): a yes covers
     * this call only and is never remembered, so a client offers no "always allow" and no switch to allowing all.
     */
    @Serializable
    @SerialName("approval")
    data class ConfirmationItem(
        override val id: Long,
        val pendingId: String,
        val domain: String = "",
        val mode: ConfirmationMode,
        val status: ConfirmationStatus = ConfirmationStatus.PENDING,
        val createdAt: Long,
        val resolvedAt: Long? = null,
        val alwaysAsk: Boolean = false,
    ) : ConversationItem

    /** A runtime phase: intake, result, evidence. */
    @Serializable
    @SerialName("runtimePhase")
    data class RuntimePhaseItem(
        override val id: Long,
        val title: String,
        val body: String,
        val markdown: Boolean = false,
    ) : ConversationItem
}

/**
 * Whether a client shows [this] folded until the person opens it: thinking, status and runtime lines, tool calls and
 * runtime phases are; the person's words, answers, errors and approvals are not.
 */
val ConversationItem.foldedByDefault: Boolean
    get() = when (this) {
        is ConversationItem.MessageItem -> role in FOLDED_ROLES
        is ConversationItem.ToolCallItem, is ConversationItem.RuntimePhaseItem -> true
        is ConversationItem.TurnHeader, is ConversationItem.ConfirmationItem -> false
    }

private val FOLDED_ROLES = setOf(TranscriptRole.THINKING, TranscriptRole.STATUS, TranscriptRole.RUNTIME)

/** [ConversationItem.TurnHeader.label] of a turn the person opened; a client words it in its own language. */
const val USER_TURN_LABEL = "You"

/** A person's additional input within the current turn; clients translate the label and preserve the turn boundary. */
const val USER_STEERING_LABEL = "User steering"

/** Whether this item begins a new logical turn rather than adding user input to the active one. */
val ConversationItem.isTurnStart: Boolean
    get() = this is ConversationItem.TurnHeader && label != USER_STEERING_LABEL

/** [ConversationItem.TurnHeader.label] of a turn an agent's report opened (a task delegated to it came back). */
const val DELEGATION_TURN_LABEL = "Delegation report"

/** A thread with its items. */
@Serializable
data class ThreadSnapshot(
    val threadId: String,
    val items: List<ConversationItem>,
)
