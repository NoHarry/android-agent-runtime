package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A streamed thinking block's signature reaches the loop, and the Responses API's cached tokens are counted. */
class StreamSignatureAndCacheUsageTest {
    private fun sse(vararg payloads: String) = object : HttpTransport {
        override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
            emit(HttpEvent.Response(200))
            payloads.forEach { emit(HttpEvent.Data("data: $it\n\n".toByteArray())) }
            emit(HttpEvent.Done)
        }
        override suspend fun execute(request: HttpRequest): HttpResponse = error("streaming only")
    }

    private val request = ModelRequest(messages = listOf(ModelMessage("user", "我有点冷")))

    @Test fun anAnthropicThinkingBlocksSignatureIsEmittedWhenItArrives() = runBlocking {
        val events = AnthropicModelClient(
            { "unit-test-key" }, model = "claude-sonnet", baseUrl = "https://provider.test", stream = true,
            transport = sse(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先看温度。"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQBsig"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_stop"}""",
            ),
        ).complete(request).toList()
        val thinking = events.filterIsInstance<ModelEvent.ThinkingDelta>()
        assertEquals("先看温度。", thinking.joinToString("") { it.text })
        assertEquals("EqQBsig", thinking.last().signature)
    }

    @Test fun theResponsesApisCachedInputTokensAreCounted() = runBlocking {
        val events = OpenAiModelClient(
            { "unit-test-key" }, model = "gpt-5", baseUrl = "https://provider.test/v1", stream = true, endpoint = "responses",
            transport = sse(
                """{"type":"response.output_text.delta","delta":"好"}""",
                """{"type":"response.completed","response":{"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":1024},"output_tokens":30}}}""",
            ),
        ).complete(request).toList()
        assertEquals(1024, events.filterIsInstance<ModelEvent.UsageDelta>().sumOf { it.cacheReadTokens })
    }
}
