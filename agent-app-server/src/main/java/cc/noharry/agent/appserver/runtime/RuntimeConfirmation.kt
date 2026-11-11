package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.AgentThread
import cc.noharry.agent.Approval
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.ApprovalScope
import cc.noharry.agent.RespondResult
import cc.noharry.agent.appserver.protocol.ConfirmationMode

internal data class ConfirmationPostUiResult(val status: String, val transcript: SteeringTranscript)

internal suspend fun postRuntimeConfirmation(
    thread: AgentThread,
    pendingId: String,
    approve: Boolean,
    scope: ApprovalScope = ApprovalScope.ONCE,
): ConfirmationPostUiResult {
    val pending = thread.pendingRequests().filterIsInstance<Approval>().firstOrNull { it.id == pendingId }
    val result = if (pending == null) RespondResult.NOT_FOUND else if (approve) pending.approve(scope) else pending.deny()
    return when (result) {
        RespondResult.ACCEPTED -> ConfirmationPostUiResult("Confirmation sent", SteeringTranscript.Status("Confirmation decision accepted."))
        RespondResult.ALREADY_ANSWERED -> ConfirmationPostUiResult("Confirmation consumed", SteeringTranscript.Status("Confirmation was already resolved."))
        RespondResult.EXPIRED -> ConfirmationPostUiResult("Confirmation expired", SteeringTranscript.Status("Confirmation expired before a decision was applied."))
        RespondResult.NOT_FOUND -> ConfirmationPostUiResult("Confirmation missing", SteeringTranscript.Status("Confirmation was not found."))
    }
}

/** In 批准过的不再问 mode a yes covers the rest of the conversation; otherwise it covers this call. */
internal fun ConfirmationMode.approvalScope(): ApprovalScope =
    if (this == ConfirmationMode.ALLOW_CONFIRMED_DOMAINS) ApprovalScope.THREAD else ApprovalScope.ONCE

/** What the card names: the site a web page comes from; other tools have no site to name. */
internal fun approvalSite(domain: String): String = domain.removePrefix(WEB_FETCH_AREA).takeIf { domain.startsWith(WEB_FETCH_AREA) }.orEmpty()

private const val WEB_FETCH_AREA = "web_fetch:"

/**
 * The runtime's policy for a turn in this mode. A call a gate marks to ask every time (a plugin's or package's tool that
 * acts, [PluginToolGate]) asks in every mode but "deny all", which refuses it.
 */
internal fun ConfirmationMode.approvalPolicy(): ApprovalPolicy = when (this) {
    ConfirmationMode.ASK_EVERY_TIME -> ApprovalPolicy.ASK
    ConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> ApprovalPolicy.REMEMBER
    ConfirmationMode.ALLOW_ALL_SESSION -> ApprovalPolicy.APPROVE_ALL
    ConfirmationMode.DENY_ALL -> ApprovalPolicy.DENY_ALL
}
