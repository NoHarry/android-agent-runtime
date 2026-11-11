package cc.noharry.agent.appserver.models.deepseek

import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** DeepSeek's endpoints (OpenAI-compatible and Anthropic-compatible) take one key: a profile there with none takes another's. */
class DeepSeekKeysTest {
    @Test
    fun theAnthropicEndpointsProfileInheritsTheApiProfilesKeyWhenItsOwnIsBlank() {
        val shared = DeepSeekAdapter.shareKeys(listOf(api("sk-shared-deepseek"), anthropic(""))).associateBy { it.id }

        assertEquals("sk-shared-deepseek", shared.getValue("api").apiKey)
        assertEquals("sk-shared-deepseek", shared.getValue("anthropic").apiKey)
    }

    @Test
    fun anExplicitKeyOnEitherIsKeptAndAProfileElsewhereGetsNone() {
        val relay = ProviderProfile("relay", "relay", ModelProvider.OPENAI, "", "deepseek-flash", "https://relay.example.com/v1")
        val kept = DeepSeekAdapter.shareKeys(listOf(api("sk-openai-port"), anthropic("sk-anthropic-port"), relay)).associateBy { it.id }

        assertEquals("sk-openai-port", kept.getValue("api").apiKey)
        assertEquals("sk-anthropic-port", kept.getValue("anthropic").apiKey)
        assertEquals("", kept.getValue("relay").apiKey)
    }

    private fun api(key: String) = ProviderProfile(
        "api", "deepseek api", ModelProvider.OPENAI, key, "deepseek-flash", "https://api.deepseek.com/v1",
        ProviderEndpoint.CHAT_COMPLETIONS,
    )

    private fun anthropic(key: String) = ProviderProfile(
        "anthropic", "deepseek anthropic", ModelProvider.ANTHROPIC, key, "deepseek-flash",
        "https://api.deepseek.com/anthropic", ProviderEndpoint.MESSAGES,
    )
}
