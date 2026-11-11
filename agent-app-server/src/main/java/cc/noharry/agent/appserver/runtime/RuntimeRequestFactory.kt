package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest

internal fun agentRequest(
    requestId: String,
    sessionId: String,
    input: String,
    config: ProviderConfig,
    profile: ProviderProfile,
): RuntimeTurnRequest =
    RuntimeTurnRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = AppHost.scene,
        modelRef = config.modelRef(profile),
    )
