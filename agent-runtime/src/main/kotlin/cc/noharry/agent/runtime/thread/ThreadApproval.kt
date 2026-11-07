@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.PendingConfirmation
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    override val domain = approvalDomain(tool, arguments)
    override val reason = pending.prompt
    private val decision = CompletableDeferred<ApprovalState>()
    @Volatile override var state: ApprovalState = ApprovalState.PENDING
        private set
    @Volatile var scope: ApprovalScope = ApprovalScope.ONCE
        private set
    override val status: ItemStatus get() = when (state) {
        ApprovalState.PENDING -> ItemStatus.IN_PROGRESS
        ApprovalState.APPROVED -> ItemStatus.COMPLETED
        else -> ItemStatus.DECLINED
    }

    override suspend fun approve(scope: ApprovalScope): RespondResult = respond(ApprovalState.APPROVED, scope)
    override suspend fun deny(reason: String?): RespondResult = respond(ApprovalState.DENIED, ApprovalScope.ONCE)

    private fun respond(value: ApprovalState, scope: ApprovalScope): RespondResult = synchronized(this) {
        if (state == ApprovalState.PENDING && clock() >= expiresAt) complete(ApprovalState.EXPIRED)
        when (state) {
            ApprovalState.EXPIRED -> RespondResult.EXPIRED
            ApprovalState.CANCELLED -> RespondResult.NOT_FOUND
            ApprovalState.PENDING -> {
                this.scope = scope
                complete(value)
                RespondResult.ACCEPTED
            }
            else -> RespondResult.ALREADY_ANSWERED
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

internal fun approvalDomain(tool: String, arguments: String): String {
    if (tool.startsWith("mcp__")) return tool.removePrefix("mcp__").substringBefore("__")
    if (tool == "web_fetch") {
        val json = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        val url = (json?.get("url") as? JsonPrimitive)?.content
        runCatching { url?.let { URI(it).host } }.getOrNull()?.let { return "web_fetch:$it" }
    }
    return tool
}
