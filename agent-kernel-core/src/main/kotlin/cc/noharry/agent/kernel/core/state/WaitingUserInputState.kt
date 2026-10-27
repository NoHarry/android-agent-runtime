package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff

data class WaitingUserInputState(
    val handoff: RuntimeHandoff,
    val openConfusion: OpenConfusion,
    val blocker: RuntimeBlocker,
)
