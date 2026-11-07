@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.ToolSource

/**
 * An immutable snapshot of a visible part of a turn. Its id is stable throughout its lifecycle.
 * Live tool arguments and outputs are unfiltered; persisted history follows the storage redactor.
 */
sealed interface ThreadItem {
    val id: String
    val turnId: String
    val status: ItemStatus

    data class UserMessage(
        override val id: String,
        override val turnId: String,
        val input: List<InputPart>,
        val steer: Boolean = false,
        override val status: ItemStatus = ItemStatus.COMPLETED,
    ) : ThreadItem

    data class AgentMessage(
        override val id: String,
        override val turnId: String,
        val text: String,
        val phase: MessagePhase = MessagePhase.COMMENTARY,
        override val status: ItemStatus = ItemStatus.IN_PROGRESS,
    ) : ThreadItem

    data class Reasoning(
        override val id: String,
        override val turnId: String,
        val text: String,
        override val status: ItemStatus = ItemStatus.IN_PROGRESS,
    ) : ThreadItem

    data class ToolCall(
        override val id: String,
        override val turnId: String,
        val tool: String,
        val source: ToolSource,
        val server: String? = null,
        val arguments: String = "{}",
        val output: String? = null,
        override val status: ItemStatus = ItemStatus.IN_PROGRESS,
        val network: NetworkActivity? = null,
        val approvedBy: ApprovalPolicy? = null,
    ) : ThreadItem

    data class WebSearch(
        override val id: String,
        override val turnId: String,
        val provider: String,
        val query: String,
        val resultCount: Int? = null,
        override val status: ItemStatus = ItemStatus.COMPLETED,
    ) : ThreadItem

    data class FileChange(
        override val id: String,
        override val turnId: String,
        val path: String,
        val added: Int,
        val removed: Int,
        val tool: String,
        override val status: ItemStatus = ItemStatus.COMPLETED,
    ) : ThreadItem

    data class Notice(
        override val id: String,
        override val turnId: String,
        val kind: NoticeKind,
        val message: String,
        override val status: ItemStatus = ItemStatus.COMPLETED,
    ) : ThreadItem

    data class Error(
        override val id: String,
        override val turnId: String,
        val code: String,
        val message: String,
        val recoverable: Boolean,
        override val status: ItemStatus = ItemStatus.FAILED,
    ) : ThreadItem
}

enum class ItemStatus { IN_PROGRESS, COMPLETED, FAILED, DECLINED }
enum class MessagePhase { COMMENTARY, FINAL }
enum class NoticeKind { RETRY, COMPACTION, BUDGET_LOW, WORKSPACE_CHANGED }

/** Metadata about a network tool; payloads remain on the tool item. */
data class NetworkActivity(val domain: String, val decision: String, val statusCode: Int?, val truncated: Boolean)

/** An approval gate or a question. The expiry is in Unix epoch milliseconds, or null for a durable question. */
sealed interface PendingRequest : ThreadItem {
    val expiresAt: Long?
}

/**
 * Approval methods are idempotent and safe to call from another collector or screen.
 * Only the first decision takes effect. The original turn stays open until a decision or expiry.
 */
abstract class Approval : PendingRequest {
    open val state: ApprovalState get() = ApprovalState.PENDING
    abstract val tool: String
    abstract val arguments: String
    abstract val domain: String
    abstract val reason: String
    abstract suspend fun approve(scope: ApprovalScope = ApprovalScope.ONCE): RespondResult
    abstract suspend fun deny(reason: String? = null): RespondResult
}

/**
 * A blocking question completes its turn; the next input answers it (optionally using TurnInput.answering).
 * An asynchronous question remains open only while its turn runs and is answered with AgentThread.steer.
 */
data class Question(
    override val id: String,
    override val turnId: String,
    val text: String,
    val choices: List<String> = emptyList(),
    val async: Boolean = false,
    override val expiresAt: Long? = null,
    override val status: ItemStatus = ItemStatus.COMPLETED,
) : PendingRequest

enum class RespondResult { ACCEPTED, NOT_FOUND, EXPIRED, ALREADY_ANSWERED }
enum class ApprovalState { PENDING, APPROVED, DENIED, EXPIRED, CANCELLED }
