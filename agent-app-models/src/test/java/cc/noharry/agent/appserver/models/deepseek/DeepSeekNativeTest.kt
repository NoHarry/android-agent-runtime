package cc.noharry.agent.appserver.models.deepseek
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekNativeTest {
    private fun profile(baseUrl: String, endpoint: ProviderEndpoint = ProviderEndpoint.MESSAGES) =
        ProviderProfile("p", "p", ModelProvider.ANTHROPIC, "k", "deepseek-flash", baseUrl, endpoint)

    @Test fun onlyDeepSeeksOwnMessagesEndpointGetsNativeUpdates() {
        assertTrue(profile("https://api.deepseek.com/anthropic").usesDeepSeekNativeUpdates())
        // A gateway in front of DeepSeek drops defer_loading and tool_addition.
        assertFalse(profile("https://api.noharry.cc/v1").usesDeepSeekNativeUpdates())
        assertFalse(profile("https://api.deepseek.com.example.org/anthropic").usesDeepSeekNativeUpdates())
    }
}
