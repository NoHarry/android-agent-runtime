package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientFactory
import cc.noharry.agent.a2a.A2aClientModule
import cc.noharry.agent.a2a.ExperimentalA2aClientApi

/** Phase 0 candidate module backed by the official A2A Java SDK 1.1.0.Final. */
@ExperimentalA2aClientApi
public class OfficialA2aClientModule public constructor() : A2aClientModule {
    /** Returns a factory that creates executable official-SDK challenger clients. */
    override fun factory(): A2aClientFactory = A2aClientFactory { config ->
        OfficialA2aClient(config, DefaultOfficialSdkBridge(config))
    }
}
