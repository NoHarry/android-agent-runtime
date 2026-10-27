@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A runtime's system prompt describes the web tools it actually offers. */
class ProtocolPromptRuntimeTest {
    private class Recorder : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        val seen = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest) =
            flowOf(ModelEvent.TextDelta("好的。"), ModelEvent.Completed).also { seen += request }
    }

    private fun firstRequest(configure: AgentRuntimeBuilder.() -> Unit): ModelRequest {
        val model = Recorder()
        val runtime = AgentRuntimeBuilder.create().modelClient(model).apply(configure).build()
        runBlocking { runtime.run(AgentRequest("r1", "s1", "你好")).toList() }
        runtime.close()
        return model.seen.first()
    }

    @Test fun hiddenWebFetchIsNotDescribed() {
        val request = firstRequest { nativeWebFetchEnabled(false) }
        assertFalse(request.tools.any { it.name == "web_fetch" })
        assertFalse(request.system.orEmpty().contains("web_fetch"))
    }

    @Test fun offeredWebFetchIsDescribed() {
        val request = firstRequest { }
        assertTrue(request.tools.any { it.name == "web_fetch" })
        assertTrue(request.system.orEmpty().contains("web_fetch returns extracted page text"))
    }
}
