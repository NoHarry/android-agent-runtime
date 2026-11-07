package cc.noharry.agent.model

import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenAiMissingToolIdsTest {
    @Test fun missingAndBlankChatIdsAreDistinctWithinAndAcrossResponses() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{"tool_calls":[
            {"function":{"name":"first","arguments":"{}"}},
            {"id":"","function":{"name":"second","arguments":"{}"}},
            {"id":"provider-id","function":{"name":"third","arguments":"{}"}}
        ]}}]}"""))
        val client = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
        val rounds = List(2) { client.complete(contractModelRequest()).toList().filterIsInstance<ModelEvent.ToolCall>() }
        rounds.forEach { calls ->
            assertEquals(listOf("first", "second", "third"), calls.map { it.name })
            assertEquals("provider-id", calls.last().id)
        }
        assertEquals(4, rounds.flatMap { it.take(2) }.map { it.id }.toSet().size)
        assertTrue(rounds.flatten().all { it.id.isNotBlank() })
    }

    @Test fun streamedMissingIdsStayAttachedToTheirArgumentFragmentsAndAreUnique() = runBlocking {
        val transport = FakeHttpTransport(streamText = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"first","arguments":"{\"value\":"}},{"index":1,"id":"","function":{"name":"second","arguments":"{\"value\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}},{"index":0,"function":{"arguments":"1}"}}]}}]}

            data: [DONE]
        """.trimIndent())
        val client = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, stream = true, transport = transport)
        val rounds = List(2) { client.complete(contractModelRequest()).toList().filterIsInstance<ModelEvent.ToolCall>() }
        rounds.forEach { calls ->
            assertEquals(listOf("first", "second"), calls.map { it.name })
            assertEquals(listOf("""{"value":1}""", """{"value":2}"""), calls.map { it.argumentsJson })
        }
        assertEquals(4, rounds.flatten().map { it.id }.toSet().size)
    }
}
