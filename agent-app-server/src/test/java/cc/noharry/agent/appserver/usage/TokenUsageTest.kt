package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.TokenUsageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageTest {
    @Test
    fun parsesOpenAiCompatibleUsage() {
        val delta = parseTokenUsage(
            """
                {
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30,
                    "total_tokens": 150,
                    "prompt_tokens_details": {"cached_tokens": 60}
                  }
                }
            """.trimIndent(),
        )

        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        assertEquals(120, state.inputTokens)
        assertEquals(30, state.outputTokens)
        assertEquals(150, state.totalTokens)
        assertEquals(60, state.cachedInputTokens)
        assertEquals(50, state.cacheHitRate)
    }

    @Test
    fun parsesAServicesOwnCacheHitAndMissFields() {
        val delta = parseTokenUsage(
            """
                {"usage":{
                  "prompt_tokens":150,
                  "completion_tokens":20,
                  "total_tokens":170,
                  "cache_hit_tokens":100,
                  "cache_miss_tokens":50
                }}
            """.trimIndent(),
            listOf(cc.noharry.agent.appserver.model.UsageFields("cache_hit_tokens", "cache_miss_tokens")),
        )

        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        assertEquals(100, state.cachedInputTokens)
        assertEquals(66, state.cacheHitRate)
    }

    @Test
    fun parsesAnthropicCompatibleSseUsage() {
        val delta = parseTokenUsage(
            """
                data: {"type":"message_delta","usage":{"output_tokens":1}}
                data: {"type":"message_stop","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":4,"cache_creation_input_tokens":6}}
                data: [DONE]
            """.trimIndent(),
        )

        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        // Anthropic input_tokens excludes cache fields: 10 + 4 + 6 = 20
        assertEquals(20, state.inputTokens)
        assertEquals(5, state.outputTokens)
        assertEquals(25, state.totalTokens)
        assertEquals(4, state.cachedInputTokens)
        assertEquals(6, state.cacheWriteTokens)
        assertEquals(20, state.cacheHitRate)
    }

    @Test
    fun anthropicWarmCacheIsNotAlwaysOneHundredPercent() {
        // Common warm-cache shape: large read, zero creation, small uncached input.
        val delta = parseTokenUsage(
            """
                {"usage":{
                  "input_tokens":100,
                  "output_tokens":20,
                  "cache_read_input_tokens":1900,
                  "cache_creation_input_tokens":0
                }}
            """.trimIndent(),
        )

        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        assertEquals(2000, state.inputTokens)
        assertEquals(1900, state.cachedInputTokens)
        assertEquals(0, state.cacheWriteTokens)
        assertEquals(95, state.cacheHitRate)
    }

    @Test
    fun mapsAgentEventTokenUsageWithoutDoubleCountingInclusiveInput() {
        val delta = cc.noharry.agent.Usage(
            inputTokens = 2000,
            outputTokens = 20,
            cacheReadTokens = 1900,
            cacheWriteTokens = 0,
        ).toUsageDelta()
        val state = TokenUsageState().plus(delta)
        assertEquals(2000, state.inputTokens)
        assertEquals(95, state.cacheHitRate)
    }

    @Test
    fun parsesAnthropicMessageStartNestedUsage() {
        val delta = parseTokenUsage(
            """
                data: {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":0,"cache_read_input_tokens":88,"cache_creation_input_tokens":0}}}
                data: [DONE]
            """.trimIndent(),
        )
        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        assertEquals(100, state.inputTokens)
        assertEquals(88, state.cacheHitRate)
    }

    @Test
    fun parsesOpenAiResponsesNestedStreamingUsage() {
        val delta = parseTokenUsage(
            """
                data: {"type":"response.created","response":{"usage":null}}
                data: {"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":10,"total_tokens":110,"input_tokens_details":{"cached_tokens":40}}}}
                data: [DONE]
            """.trimIndent(),
        )

        assertNotNull(delta)
        val state = TokenUsageState().plus(delta!!)
        assertEquals(100, state.inputTokens)
        assertEquals(10, state.outputTokens)
        assertEquals(110, state.totalTokens)
        assertEquals(40, state.cachedInputTokens)
        assertEquals(40, state.cacheHitTokens)
        assertEquals(60, state.cacheMissTokens)
        assertEquals(40, state.cacheHitRate)
    }

    @Test
    fun ignoresResponsesWithoutUsage() {
        assertNull(parseTokenUsage("""{"choices":[]}"""))
    }
}
