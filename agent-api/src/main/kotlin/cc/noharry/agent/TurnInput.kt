@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.SessionAttachmentRef
import cc.noharry.agent.spi.SessionRunConflictPolicy
import cc.noharry.agent.spi.model.ModelRef

/** A user message, optionally answering a particular pending question. */
data class TurnInput(val parts: List<InputPart>, val answering: String? = null) {
    init { require(parts.isNotEmpty()) { "Input must contain at least one part" } }

    companion object {
        @JvmStatic fun text(text: String): TurnInput = TurnInput(listOf(InputPart.Text(text)))
        @JvmStatic fun of(vararg parts: InputPart): TurnInput = TurnInput(parts.toList())
    }
}

/** Text, an image, or an attachment already stored in this thread's attachment store. */
sealed interface InputPart {
    data class Text(val text: String) : InputPart
    /** The runtime snapshots the bytes when a turn is collected. */
    data class Image(val bytes: ByteArray, val mimeType: String) : InputPart
    /**
     * Resolved from this thread's SessionEventLog attachment store. UTF-8 text media types and application/json become text;
     * PNG, JPEG, and WebP become model images. Missing, corrupt, and unsupported attachments fail the turn explicitly.
     */
    data class Attachment(val ref: SessionAttachmentRef) : InputPart
}

/**
 * Defaults for a new thread, or a patch when resuming one. Null fields retain saved values on resume; for a new
 * thread they use runtime defaults (ASK for approvals). Use [reset] to clear individual saved overrides explicitly.
 */
data class ThreadOptions(
    val threadId: String? = null,
    val model: ModelRef? = null,
    val workspace: String? = null,
    val scene: String? = null,
    val instructions: String? = null,
    val approvalPolicy: ApprovalPolicy? = null,
    val reset: Set<ThreadOption> = emptySet(),
)

/** Saved overrides that can be cleared independently while resuming a thread. Explicit values take precedence. */
enum class ThreadOption { MODEL, WORKSPACE, SCENE, INSTRUCTIONS }

/** Overrides for one turn. A caller-supplied turn id is the idempotency key. */
data class TurnOptions(
    val turnId: String? = null,
    val model: ModelRef? = null,
    val currentStateKeys: Set<String> = emptySet(),
    /** Null and QUEUE_NEW queue behind the active turn; RESUME_CURRENT fails with THREAD_BUSY; CANCEL_AND_REPLACE interrupts it. */
    val onConflict: SessionRunConflictPolicy? = null,
    val approvalPolicy: ApprovalPolicy? = null,
)

/** Applies when a tool's policy or hook requires approval; never overrides a policy denial. */
enum class ApprovalPolicy { ASK, REMEMBER, APPROVE_ALL, DENY_ALL }
/**
 * THREAD saves the approved area for subsequent turns using REMEMBER. An explicit ASK policy still asks on every
 * approval gate, and DENY_ALL still denies it; the turn's policy takes precedence over stored approvals.
 */
enum class ApprovalScope { ONCE, THREAD }
enum class ThreadStatus { IDLE, RUNNING, WAITING_FOR_APPROVAL, WAITING_FOR_ANSWER }
enum class TurnStatus { COMPLETED, WAITING_FOR_ANSWER, INTERRUPTED, FAILED }

/** Timestamps are Unix epoch milliseconds. */
data class ThreadInfo(val id: String, val createdAt: Long, val updatedAt: Long, val status: ThreadStatus)

/** Token totals for the whole turn, including retries and model rounds. */
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/**
 * Machine-readable [code] with a human-readable explanation. See [TurnErrorCodes] for conversation failures and
 * [cc.noharry.agent.spi.reliability.RuntimeErrorCode] for provider/tool codes; custom providers may add their own.
 */
data class TurnError(val code: String, val message: String, val recoverable: Boolean = false)

/** Complete snapshot of one turn. A waiting turn has a [pending] question. */
data class TurnResult(
    val turnId: String,
    val status: TurnStatus,
    val finalResponse: String,
    val items: List<ThreadItem>,
    val usage: Usage? = null,
    val pending: PendingRequest? = null,
    val error: TurnError? = null,
)
