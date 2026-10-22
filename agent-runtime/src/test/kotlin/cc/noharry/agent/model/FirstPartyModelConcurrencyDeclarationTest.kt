@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstPartyModelConcurrencyDeclarationTest {
    @Test
    fun openAiAndAnthropicDeclareBoundedThreadSafe() {
        val openAi = OpenAiModelClient(apiKeyProvider = { "test-key" })
        val anthropic = AnthropicModelClient(apiKeyProvider = { "test-key" })
        val expected = ProviderConcurrencyDeclaration.threadSafe()

        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE, openAi.providerConcurrency.mode)
        assertEquals(expected, openAi.providerConcurrency)
        assertEquals(expected, anthropic.providerConcurrency)
    }
}
