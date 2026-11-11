package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.AgentThread
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnOptions
import cc.noharry.agent.spi.model.ModelRef

/** Host admission and presentation context; the SDK owns the thread and turn lifecycle. */
internal data class RuntimeTurnRequest(
    val requestId: String,
    val sessionId: String,
    val input: String,
    val scene: String? = null,
    val modelRef: ModelRef? = null,
    val workspaceId: String? = null,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.ASK,
    val currentStateKeys: Set<String> = emptySet(),
) {
    internal fun thread(runtime: AgentRuntime, policy: ApprovalPolicy = approvalPolicy): AgentThread =
        runtime.resumeThread(sessionId, ThreadOptions(model = modelRef, workspace = workspaceId, scene = scene, approvalPolicy = policy))

    internal fun options(policy: ApprovalPolicy? = null): TurnOptions =
        TurnOptions(turnId = requestId, model = modelRef, currentStateKeys = currentStateKeys, approvalPolicy = policy ?: approvalPolicy)
}
