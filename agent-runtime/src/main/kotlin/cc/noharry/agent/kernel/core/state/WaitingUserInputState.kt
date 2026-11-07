package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.spi.reliability.OpenConfusion
import cc.noharry.agent.spi.reliability.RuntimeBlocker
import cc.noharry.agent.spi.reliability.RuntimeHandoff

internal data class WaitingUserInputState(
    val handoff: RuntimeHandoff,
    val openConfusion: OpenConfusion,
    val blocker: RuntimeBlocker,
)
