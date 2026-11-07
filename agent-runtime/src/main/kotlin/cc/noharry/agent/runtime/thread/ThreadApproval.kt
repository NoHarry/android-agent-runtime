@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.PendingConfirmation
import cc.noharry.agent.runtime.webFetchApprovalHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Decision ownership is atomic; expiry and cancellation compete with responses on the same lock. */
internal class ThreadApproval(
    pending: PendingConfirmation,
    override val expiresAt: Long,
    private val clock: () -> Long,
) : Approval() {
    override val id = pending.pendingId
    override val turnId = pending.runId
    override val tool = pending.toolName.orEmpty()
    override val arguments = pending.argumentsJson ?: "{}"
    override val domain = approvalDomain(tool, arguments) ?: "web_fetch:invalid"
    override val reason = pending.prompt
    private val decision = CompletableDeferred<ApprovalState>()
    @Volatile override var state: ApprovalState = ApprovalState.PENDING
        private set
    @Volatile var scope: ApprovalScope = ApprovalScope.ONCE
        private set
    @Volatile var approvedBy: ApprovalSource? = null
        private set
    override val status: ItemStatus get() = when (state) {
        ApprovalState.PENDING -> ItemStatus.IN_PROGRESS
        ApprovalState.APPROVED -> ItemStatus.COMPLETED
        else -> ItemStatus.DECLINED
    }

    override suspend fun approve(scope: ApprovalScope): RespondResult = respond(ApprovalState.APPROVED, scope)
    override suspend fun deny(reason: String?): RespondResult = respond(ApprovalState.DENIED, ApprovalScope.ONCE)

    private fun respond(value: ApprovalState, scope: ApprovalScope, source: ApprovalSource = ApprovalSource.USER): RespondResult = synchronized(this) {
        if (state == ApprovalState.PENDING && clock() >= expiresAt) complete(ApprovalState.EXPIRED)
        when (state) {
            ApprovalState.EXPIRED -> RespondResult.EXPIRED
            ApprovalState.CANCELLED -> RespondResult.NOT_FOUND
            ApprovalState.PENDING -> {
                this.scope = scope
                approvedBy = source.takeIf { value == ApprovalState.APPROVED }
                complete(value)
                RespondResult.ACCEPTED
            }
            else -> RespondResult.ALREADY_ANSWERED
        }
    }

    fun applyPolicy(policy: ApprovalPolicy, remembered: Boolean) {
        when (policy) {
            ApprovalPolicy.APPROVE_ALL -> respond(ApprovalState.APPROVED, ApprovalScope.ONCE, ApprovalSource.AUTOMATIC)
            ApprovalPolicy.DENY_ALL -> respond(ApprovalState.DENIED, ApprovalScope.ONCE)
            ApprovalPolicy.REMEMBER -> if (remembered) respond(ApprovalState.APPROVED, ApprovalScope.THREAD, ApprovalSource.REMEMBERED)
            ApprovalPolicy.ASK -> Unit
        }
    }

    suspend fun await(): ApprovalState {
        val remaining = (expiresAt - clock()).coerceAtLeast(0)
        if (withTimeoutOrNull(remaining) { decision.await() } == null) synchronized(this) { complete(ApprovalState.EXPIRED) }
        return state
    }

    fun cancel() = synchronized(this) { complete(ApprovalState.CANCELLED) }

    fun snapshot(): Approval = StoredApproval(id, turnId, tool, arguments, domain, reason, expiresAt, status, state)

    private fun complete(value: ApprovalState) {
        if (state != ApprovalState.PENDING) return
        state = value
        decision.complete(value)
    }
}

/** Invalid network targets cannot create a remembered approval for a broader area. */
internal fun approvalDomain(tool: String, arguments: String): String? {
    if (tool.startsWith("mcp__")) {
        val qualified = tool.removePrefix("mcp__")
        return "mcp:${qualified.substringBefore("__")}:${qualified.substringAfter("__", "")}"
    }
    if (tool == "web_fetch") return webFetchApprovalHost(arguments)?.let { "web_fetch:$it" }
    return "tool:$tool"
}
