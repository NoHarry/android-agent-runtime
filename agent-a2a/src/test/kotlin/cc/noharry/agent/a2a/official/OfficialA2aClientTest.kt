package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aAuthProvider
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aMethod
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aProtectedHeaders
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class OfficialA2aClientTest {
    @Test
    fun allFacadePathsDelegateWithoutOfficialTypesInPublicEntryPoint() = runBlocking {
        val bridge = FakeBridge()
        val client = OfficialA2aClient(config(), bridge)

        assertTrue(client.getAgentCard(remote()) is A2aResult.Success)
        assertTrue(client.sendMessage(remote(), request()) is A2aResult.Success)
        assertEquals(listOf(A2aStreamEvent.Message(agentMessage()), A2aStreamEvent.Completed),
            client.streamMessage(remote(), request()).toList())
        assertEquals(A2aTask.Status.WORKING,
            (client.getTask(remote(), "t1") as A2aResult.Success).value.status)
        assertEquals(A2aTask.Status.CANCELLED,
            (client.cancelTask(remote(), "t1") as A2aResult.Success).value.status)
        assertEquals(listOf(A2aStreamEvent.Task(task(A2aTask.Status.COMPLETED)), A2aStreamEvent.Completed),
            client.resubscribeTask(remote(), "t1").toList())

        assertEquals(
            listOf(
                A2aMethod.GET_AGENT_CARD,
                A2aMethod.SEND_MESSAGE,
                A2aMethod.STREAM_MESSAGE,
                A2aMethod.GET_TASK,
                A2aMethod.CANCEL_TASK,
                A2aMethod.RESUBSCRIBE_TASK,
            ),
            bridge.authMethods,
        )
        client.close()
        assertTrue(bridge.closed)
    }

    @Test
    fun authSecretsArePassedButNeverExposedByFailures() = runBlocking {
        val secret = "official-secret-canary"
        val bridge = FakeBridge(failure = OfficialSdkFailure(A2aClientError.Transport("safe_failure")))
        val auth = A2aAuthProvider { A2aProtectedHeaders.of(mapOf("Authorization" to "Bearer $secret")) }
        val client = OfficialA2aClient(config(auth), bridge)

        val result = client.sendMessage(remote(), request()) as A2aResult.Failure

        assertEquals(A2aClientError.Transport("safe_failure"), result.error)
        assertEquals("Bearer $secret", bridge.lastHeaders["authorization"])
        assertFalse(result.error.toString().contains(secret))
    }

    @Test
    fun invalidUriReservedHeadersAndClosedClientFailBeforeSdk() = runBlocking {
        val bridge = FakeBridge()
        val reserved = A2aAuthProvider { A2aProtectedHeaders.of(mapOf("Content-Type" to "secret")) }
        val reservedClient = OfficialA2aClient(config(reserved), bridge)
        val conflict = reservedClient.sendMessage(remote(), request()) as A2aResult.Failure
        assertEquals(A2aClientError.Configuration("reserved_header_conflict"), conflict.error)

        val insecure = OfficialA2aClient(A2aClientConfig(), bridge)
        val insecureResult = insecure.getAgentCard(
            RemoteAgentRef("remote", URI("http://example.com/rpc")),
        ) as A2aResult.Failure
        assertEquals(A2aClientError.Configuration("insecure_remote_uri"), insecureResult.error)

        reservedClient.close()
        val closed = reservedClient.getTask(remote(), "t1") as A2aResult.Failure
        assertEquals(A2aClientError.Configuration("client_closed"), closed.error)
    }

    private fun config(auth: A2aAuthProvider = A2aAuthProvider.None): A2aClientConfig = A2aClientConfig(
        authProvider = A2aAuthProvider { context ->
            bridgeAuthMethods += context.method
            auth.headers(context)
        },
        allowLoopbackHttpForDebug = true,
    )

    private fun remote(): RemoteAgentRef = RemoteAgentRef("weather", URI("http://127.0.0.1/rpc"))

    private fun request(): A2aSendMessageRequest = A2aSendMessageRequest(
        A2aMessage("m1", A2aMessage.Role.USER, listOf(A2aPart.Text("ping"))),
    )

    private fun agentMessage(): A2aMessage =
        A2aMessage("m2", A2aMessage.Role.AGENT, listOf(A2aPart.Text("pong")))

    private fun task(status: A2aTask.Status): A2aTask = A2aTask("t1", "c1", status)

    private val bridgeAuthMethods = CopyOnWriteArrayList<A2aMethod>()

    private inner class FakeBridge(
        private val failure: OfficialSdkFailure? = null,
    ) : OfficialSdkBridge {
        var closed = false
        var lastHeaders: Map<String, String> = emptyMap()
        val authMethods: List<A2aMethod> get() = bridgeAuthMethods.toList()

        override suspend fun getAgentCard(
            remote: RemoteAgentRef,
            headers: Map<String, String>,
        ): A2aAgentCard = capture(headers) {
            A2aAgentCard("weather", url = remote.endpoint, streaming = true)
        }

        override suspend fun sendMessage(
            remote: RemoteAgentRef,
            request: A2aSendMessageRequest,
            headers: Map<String, String>,
        ): A2aSendMessageResult = capture(headers) { A2aSendMessageResult.Message(agentMessage()) }

        override fun streamMessage(
            remote: RemoteAgentRef,
            request: A2aSendMessageRequest,
            headers: Map<String, String>,
        ): Flow<A2aStreamEvent> = capture(headers) {
            flowOf(A2aStreamEvent.Message(agentMessage()), A2aStreamEvent.Completed)
        }

        override suspend fun getTask(
            remote: RemoteAgentRef,
            taskId: String,
            headers: Map<String, String>,
        ): A2aTask = capture(headers) { task(A2aTask.Status.WORKING) }

        override suspend fun cancelTask(
            remote: RemoteAgentRef,
            taskId: String,
            headers: Map<String, String>,
        ): A2aTask = capture(headers) { task(A2aTask.Status.CANCELLED) }

        override fun resubscribeTask(
            remote: RemoteAgentRef,
            taskId: String,
            headers: Map<String, String>,
        ): Flow<A2aStreamEvent> = capture(headers) {
            flowOf(A2aStreamEvent.Task(task(A2aTask.Status.COMPLETED)), A2aStreamEvent.Completed)
        }

        override fun close() {
            closed = true
        }

        private fun <T> capture(headers: Map<String, String>, value: () -> T): T {
            lastHeaders = headers
            failure?.let { throw it }
            return value()
        }
    }
}
