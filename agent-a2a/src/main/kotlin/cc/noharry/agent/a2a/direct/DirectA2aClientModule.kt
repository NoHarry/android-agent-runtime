package cc.noharry.agent.a2a.direct

import cc.noharry.agent.a2a.A2aClientFactory
import cc.noharry.agent.a2a.A2aClientModule
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import okhttp3.OkHttpClient

/** Phase 0 direct JSON-RPC/SSE implementation module. */
@ExperimentalA2aClientApi
public class DirectA2aClientModule public constructor() : A2aClientModule {
    private val baseClient: OkHttpClient = OkHttpClient()

    internal constructor(baseClient: OkHttpClient) : this() {
        injectedClient = baseClient
    }

    private var injectedClient: OkHttpClient? = null

    /** Returns a factory that creates executable direct A2A clients. */
    override fun factory(): A2aClientFactory = A2aClientFactory { config ->
        DirectA2aClient(config, injectedClient ?: baseClient)
    }
}
