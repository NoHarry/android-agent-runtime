package cc.noharry.agent.a2a.integration

import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.agent.a2a.direct.DirectA2aClientModule
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class ReferenceIntegrationSmokeTest {
    @Test
    fun officialHelloWorldCardSyncAndStream() = runBlocking {
        val baseUrl = requiredProperty("a2a.reference.baseUrl")
        client().use { client ->
            val remote = remote(baseUrl)
            val card = client.getAgentCard(remote) as A2aResult.Success
            assertEquals("Hello World Agent", card.value.name)
            assertTrue(card.value.streaming)

            val sync = client.sendMessage(remote, request()) as A2aResult.Success
            assertTrue(sync.value is A2aSendMessageResult.Task)
            val stream = client.streamMessage(remote, request()).toList()
            assertTrue(stream.any { it is A2aStreamEvent.Task })
            assertEquals(A2aStreamEvent.Completed, stream.last())
        }
    }

    @Test
    fun deterministicCancelRaceRejectsLateBusinessResponse() = runBlocking {
        val baseUrl = requiredProperty("a2a.reference.baseUrl")
        client().use { client ->
            val operation = async { client.sendMessage(remote(baseUrl), request()) }
            waitUntilStarted(URI("$baseUrl/state"))
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
            URI("$baseUrl/release").toURL().readText()
            val state = waitForLateResponseAttempt(URI("$baseUrl/state"))
            assertTrue(state.contains("\"request_started\":true"))
            assertTrue(state.contains("\"response_attempted\":true"))
            assertTrue(operation.isCancelled)
        }
    }

    private fun client(): A2aClient = DirectA2aClientModule().factory().create(
        A2aClientConfig(allowLoopbackHttpForDebug = true),
    )

    private fun remote(baseUrl: String): RemoteAgentRef = RemoteAgentRef(
        id = "reference",
        endpoint = URI("$baseUrl/rpc"),
    )

    private fun request(): A2aSendMessageRequest = A2aSendMessageRequest(
        A2aMessage(
            messageId = "reference-smoke-message",
            role = A2aMessage.Role.USER,
            parts = listOf(A2aPart.Text("Hello from Android direct client")),
        ),
    )

    private suspend fun waitUntilStarted(stateUri: URI) {
        repeat(STATE_POLL_ATTEMPTS) {
            if (stateUri.toURL().readText().contains("\"request_started\":true")) return
            delay(STATE_POLL_MILLIS)
        }
        error("fault server did not observe the request")
    }

    private suspend fun waitForLateResponseAttempt(stateUri: URI): String {
        repeat(STATE_POLL_ATTEMPTS) {
            val state = stateUri.toURL().readText()
            if (state.contains("\"response_attempted\":true")) return state
            delay(STATE_POLL_MILLIS)
        }
        error("fault server did not attempt the late response")
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing system property $name" }.trimEnd('/')

    private companion object {
        const val STATE_POLL_ATTEMPTS = 100
        const val STATE_POLL_MILLIS = 25L
    }
}
