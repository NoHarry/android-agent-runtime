package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelEvent

class ModelClientStreamingContractTest {
    @Test
    fun openAiStreamingEmitsDeltasBeforeLaterChunksArrive() = runBlocking {
        val secondChunkEmitted = CompletableDeferred<Unit>()
        val firstDeltaBeforeSecondChunk = CompletableDeferred<Boolean>()
        val transport = object : HttpTransport {
            override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
                emit(HttpEvent.Response(200))
                emit(HttpEvent.Data("""data: {"choices":[{"delta":{"reasoning_content":"think"}}]}""".frame()))
                emit(HttpEvent.Data("""data: {"choices":[{"delta":{"content":" visible"}}]}""".frame()))
                secondChunkEmitted.complete(Unit)
                emit(HttpEvent.Done)
            }

            override suspend fun execute(request: HttpRequest): HttpResponse =
                error("execute should not be called for streaming test")
        }
        val events = mutableListOf<ModelEvent>()

        OpenAiModelClient(
            apiKeyProvider = { "unit-test-key" },
            model = "provider-test-model",
            baseUrl = "https://provider.test/v1",
            stream = true,
            transport = transport,
        ).complete(ModelRequest(messages = listOf(ModelMessage("user", "make a plan"))))
            .collect { event ->
                if (event is ModelEvent.ThinkingDelta && !firstDeltaBeforeSecondChunk.isCompleted) {
                    firstDeltaBeforeSecondChunk.complete(!secondChunkEmitted.isCompleted)
                }
                events += event
            }

        assertTrue(firstDeltaBeforeSecondChunk.await())
        assertEquals(
            listOf("ThinkingDelta", "TextDelta", "Completed"),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun openAiStreamingPreservesUtf8SplitAcrossHttpChunks() = runBlocking {
        val frame = """data: {"choices":[{"delta":{"content":"座椅"}}]}""" + "\n\n"
        val bytes = frame.toByteArray(Charsets.UTF_8)
        val split = frame.substringBefore("座").toByteArray(Charsets.UTF_8).size + 1
        val transport = object : HttpTransport {
            override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
                emit(HttpEvent.Response(200))
                emit(HttpEvent.Data(bytes.copyOfRange(0, split)))
                emit(HttpEvent.Data(bytes.copyOfRange(split, bytes.size)))
                emit(HttpEvent.Done)
            }

            override suspend fun execute(request: HttpRequest): HttpResponse =
                error("execute should not be called for streaming test")
        }

        val events = OpenAiModelClient(
            apiKeyProvider = { "unit-test-key" },
            model = "provider-test-model",
            baseUrl = "https://provider.test/v1",
            stream = true,
            transport = transport,
        ).complete(ModelRequest(messages = listOf(ModelMessage("user", "make a plan")))).toList()

        assertEquals("座椅", events.filterIsInstance<ModelEvent.TextDelta>().single().text)
    }
}

private fun String.frame(): ByteArray =
    (this + "\n\n").toByteArray(Charsets.UTF_8)
