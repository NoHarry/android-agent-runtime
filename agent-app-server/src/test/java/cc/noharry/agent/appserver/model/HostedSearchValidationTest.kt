@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedSearchValidationTest {
    @Test
    fun providerOnlyValidationDoesNotExposeLocalTools() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val profile = ProviderProfile(
            id = "deepseek-api",
            label = "deepseek api",
            provider = ModelProvider.OPENAI,
            apiKey = "sk-test",
            model = "deepseek-v4-flash",
            baseUrl = "https://api.deepseek.com/v1",
            searchCapability = "openai-chat-options",
        )
        val config = ProviderConfig(
            values = emptyMap(),
            sourcePath = "test",
            expectedPaths = emptyList(),
            profiles = listOf(profile),
            defaultProfileId = profile.id,
            activeProfileId = profile.id,
            manualProfileEnabled = false,
        )

        val result = runHostedSearchValidation(
            requestId = "validation-1",
            profile = profile,
            config = config,
            client = HostedSearchFakeModelClient(seen),
            prompt = "find docs",
        )

        assertTrue(result.passed)
        assertEquals(0, seen.single().tools.size)
        assertEquals(listOf(ModelMessage(role = "user", content = "find docs")), seen.single().messages)
        assertEquals(1, result.searchEventCount)
        assertEquals(2, result.resultCount)
        assertEquals(0, result.toolCallCount)
        assertEquals(null, seen.single().toolChoice)
    }

    @Test
    fun openAiResponsesValidationRequiresHostedSearchAndUsesStableCacheKey() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val profile = ProviderProfile(
            id = "responses-api",
            label = "responses api",
            provider = ModelProvider.OPENAI,
            apiKey = "sk-test",
            model = "gpt-5-mini",
            baseUrl = "https://api.openai.test/v1",
            endpoint = ProviderEndpoint.RESPONSES,
            searchCapability = "openai-responses-tool",
        )
        val config = ProviderConfig(
            values = emptyMap(),
            sourcePath = "test",
            expectedPaths = emptyList(),
            profiles = listOf(profile),
            defaultProfileId = profile.id,
            activeProfileId = profile.id,
            manualProfileEnabled = false,
        )

        runHostedSearchValidation(
            requestId = "validation-2",
            profile = profile,
            config = config,
            client = HostedSearchFakeModelClient(seen),
            prompt = "find news",
        )

        assertEquals("required", seen.single().toolChoice)
        assertTrue(seen.single().promptCacheKey.orEmpty().startsWith("agent-runtime:web-search:"))
        assertTrue(seen.single().promptCacheKey.orEmpty().length <= 64)
    }
}

private class HostedSearchFakeModelClient(
    private val seen: MutableList<ModelRequest>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        seen += request
        emit(ModelEvent.SearchActivity(provider = "OpenAI", resultCount = 2))
        emit(ModelEvent.TextDelta("DeepSeek + Claude Code Integration https://api-docs.deepseek.com/guides/claude_code"))
        emit(ModelEvent.Completed)
    }
}
