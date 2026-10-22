package cc.noharry.agent.a2a.direct

import cc.noharry.agent.a2a.A2aAuthProvider
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aProtectedHeaders
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class DirectA2aClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cardDiscoveryUsesWellKnownPathAndDecodesCard() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"weather","description":"Forecasts","url":"${server.url("/rpc")}","protocolVersion":"1.0","capabilities":{"streaming":true}}""",
            ),
        )
        client().use { client ->
            val result = client.getAgentCard(remote())
            assertTrue(result is A2aResult.Success)
            val card = (result as A2aResult.Success).value
            assertEquals("weather", card.name)
            assertTrue(card.streaming)
            assertEquals("/.well-known/agent-card.json", server.takeRequest().path)
        }
    }

    @Test
    fun cardDiscoveryRejectsRedirectWithoutFollowingUnvalidatedTarget() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://127.0.0.1:1/untrusted-card"),
        )
        client().use { client ->
            val result = client.getAgentCard(remote()) as A2aResult.Failure

            assertEquals(A2aClientError.Discovery("card_http_failure"), result.error)
            assertEquals(1, server.requestCount)
            assertEquals("/.well-known/agent-card.json", server.takeRequest().path)
        }
    }

    @Test
    fun cardDiscoveryRejectsHostMerelyStartingWith127() = runBlocking {
        val result = discoverCardAdvertising("http://127.evil.example/rpc") as A2aResult.Failure

        assertEquals(A2aClientError.Configuration("insecure_remote_uri"), result.error)
    }

    @Test
    fun cardDiscoveryAcceptsIpv4LoopbackLowerBound() = runBlocking {
        val result = discoverCardAdvertising("http://127.0.0.1/rpc")

        assertTrue(result is A2aResult.Success)
    }

    @Test
    fun cardDiscoveryAcceptsIpv4LoopbackUpperBound() = runBlocking {
        val result = discoverCardAdvertising("http://127.255.255.255/rpc")

        assertTrue(result is A2aResult.Success)
    }

    @Test
    fun cardDiscoveryRejectsNonLoopbackIpv4() = runBlocking {
        val result = discoverCardAdvertising("http://128.0.0.1/rpc") as A2aResult.Failure

        assertEquals(A2aClientError.Configuration("insecure_remote_uri"), result.error)
    }

    @Test
    fun syncMessageAndTaskMethodsDecodeResults() = runBlocking {
        server.dispatcher = rpcDispatcher { request ->
            val method = request.body.readUtf8()
            when {
                method.contains("message/send") ->
                    """{"kind":"message","messageId":"m2","role":"agent","parts":[{"kind":"text","text":"pong"}]}"""
                method.contains("tasks/get") -> taskJson("working")
                method.contains("tasks/cancel") -> taskJson("canceled")
                else -> error("unexpected method")
            }
        }
        client().use { client ->
            val sent = client.sendMessage(remote(), messageRequest())
            assertTrue(sent is A2aResult.Success)
            assertTrue((sent as A2aResult.Success).value is A2aSendMessageResult.Message)

            val task = client.getTask(remote(), "t1") as A2aResult.Success
            assertEquals(A2aTask.Status.WORKING, task.value.status)

            val cancelled = client.cancelTask(remote(), "t1") as A2aResult.Success
            assertEquals(A2aTask.Status.CANCELLED, cancelled.value.status)
        }
    }

    @Test
    fun rpcErrorMapsToStableRedactedError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = rpcId(request.body.readUtf8())
                return MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":"$id","error":{"code":-32001,"message":"raw remote detail"}}""",
                )
            }
        }
        client().use { client ->
            val result = client.sendMessage(remote(), messageRequest())
            val error = (result as A2aResult.Failure).error
            assertEquals(A2aClientError.Rpc(-32001), error)
            assertFalse(error.toString().contains("raw remote detail"))
        }
    }

    @Test
    fun sseFramesAreIncrementalAndTerminalIsExplicit() = runBlocking {
        server.dispatcher = sseDispatcher { id ->
            val first = rpcEnvelope(id, """{"message":{"messageId":"m2","role":"agent","parts":[{"kind":"text","text":"one"}]}}""")
            val terminal = rpcEnvelope(id, taskJson("completed"))
            "data: $first\n\ndata: $terminal\n\n"
        }
        client().use { client ->
            val events = client.streamMessage(remote(), messageRequest()).toList()
            assertTrue(events[0] is A2aStreamEvent.Message)
            assertTrue(events[1] is A2aStreamEvent.Task)
            assertEquals(A2aStreamEvent.Completed, events[2])
        }
    }

    @Test
    fun serverArtifactAndStatusMetadataStreamIsDecodedInOrder() = runBlocking {
        server.dispatcher = sseDispatcher { id ->
            val base = """"taskId":"t1","contextId":"c1""""
            val frames = listOf(
                """{"kind":"task","id":"t1","contextId":"c1","status":{"state":"submitted"},"artifacts":[]}""",
                """{"kind":"status-update",$base,"status":{"state":"working","message":{"kind":"message","messageId":"s1","role":"agent","parts":[{"kind":"text","text":"导航 正在调用"}],"metadata":{"channel":"status"}}},"final":false,"metadata":{"kind":"tool-status","phase":"started","toolName":"navi","toolCallId":"call-1","channel":"status"}}""",
                """{"kind":"artifact-update",$base,"artifact":{"artifactId":"t1-0","name":"tts","parts":[{"kind":"text","text":"好的，马上出发"}],"metadata":{"channel":"speech","kind":"tts","phase":"progress"}},"append":false,"lastChunk":true}""",
                """{"kind":"artifact-update",$base,"artifact":{"artifactId":"t1-1","name":"question","parts":[{"kind":"text","text":"走高速吗？"}],"metadata":{"questionId":"q1","options":["是","否"],"channel":"question","kind":"question","phase":"pending"}},"append":false,"lastChunk":true}""",
                """{"kind":"artifact-update",$base,"artifact":{"artifactId":"t1-2","name":"response","parts":[{"kind":"text","text":"已开始导航"}],"metadata":{"channel":"display","kind":"response","phase":"final"}},"append":false,"lastChunk":true}""",
                """{"kind":"status-update",$base,"status":{"state":"completed","message":{"kind":"message","messageId":"s2","role":"agent","parts":[{"kind":"text","text":"已开始导航"}],"metadata":{"channel":"status"}}},"final":true,"metadata":{"channel":"status"}}""",
            )
            frames.joinToString("") { "data: ${rpcEnvelope(id, it)}\n\n: keep-alive\n\n" }
        }
        client().use { client ->
            val events = client.streamMessage(remote(), messageRequest()).toList()
            assertEquals(7, events.size)
            assertEquals("t1", (events[0] as A2aStreamEvent.Task).value.id)
            val tool = events[1] as A2aStreamEvent.Task
            assertEquals(A2aTask.Status.WORKING, tool.value.status)
            assertEquals(A2aValue.StringValue("navi"), tool.metadata?.values?.get("toolName"))
            assertEquals(A2aValue.StringValue("status"), tool.value.statusMessage?.metadata?.values?.get("channel"))
            val speech = events[2] as A2aStreamEvent.Artifact
            assertEquals("t1", speech.taskId)
            assertEquals(listOf(A2aPart.Text("好的，马上出发")), speech.artifact.parts)
            assertEquals(A2aValue.StringValue("speech"), speech.artifact.metadata?.values?.get("channel"))
            val question = (events[3] as A2aStreamEvent.Artifact).artifact.metadata?.values
            assertEquals(A2aValue.ListValue(listOf(A2aValue.StringValue("是"), A2aValue.StringValue("否"))), question?.get("options"))
            assertEquals("t1-2", (events[4] as A2aStreamEvent.Artifact).artifact.artifactId)
            assertEquals(A2aTask.Status.COMPLETED, (events[5] as A2aStreamEvent.Task).value.status)
            assertEquals(A2aStreamEvent.Completed, events[6])
        }
    }

    @Test
    fun messageMetadataIsSentAndTaskArtifactsAreDecoded() = runBlocking {
        var requestBody = ""
        server.dispatcher = rpcDispatcher { request ->
            requestBody = request.body.readUtf8()
            """{"id":"t1","contextId":"c1","status":{"state":"working"},"artifacts":[{"artifactId":"t1-0","parts":[{"kind":"text","text":"hi"}],"metadata":{"channel":"speech"}}],"metadata":{"inputReceipt":{"state":"queued"}}}"""
        }
        val reply = A2aMessage(
            messageId = "m2",
            role = A2aMessage.Role.USER,
            parts = listOf(A2aPart.Text("是")),
            contextId = "c1",
            taskId = "t1",
            metadata = A2aValue.ObjectValue(mapOf("questionId" to A2aValue.StringValue("q1"))),
        )
        client().use { client ->
            val result = client.sendMessage(remote(), A2aSendMessageRequest(reply)) as A2aResult.Success
            val task = (result.value as A2aSendMessageResult.Task).value
            assertEquals("t1-0", task.artifacts.single().artifactId)
            assertEquals(A2aValue.StringValue("speech"), task.artifacts.single().metadata?.values?.get("channel"))
        }
        assertTrue(requestBody.contains(""""metadata":{"questionId":"q1"}"""))
        assertTrue(requestBody.contains(""""taskId":"t1""""))
    }

    @Test
    fun sseDisconnectWithoutTerminalEmitsIncomplete() = runBlocking {
        server.dispatcher = sseDispatcher { id ->
            val event = rpcEnvelope(id, """{"message":{"messageId":"m2","role":"agent","parts":[{"kind":"text","text":"partial"}]}}""")
            "data: $event\n\n"
        }
        client().use { client ->
            val events = client.streamMessage(remote(), messageRequest()).toList()
            assertTrue(events.first() is A2aStreamEvent.Message)
            assertTrue(events.last() is A2aStreamEvent.Incomplete)
        }
    }

    @Test
    fun resubscribeUsesTaskMethodAndCompletes() = runBlocking {
        val usedResubscribe = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                usedResubscribe.set(body.contains("tasks/resubscribe"))
                return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: ${rpcEnvelope(rpcId(body), taskJson("completed"))}\n\n")
            }
        }
        client().use { client ->
            val events = client.resubscribeTask(remote(), "t1").toList()
            assertTrue(events.first() is A2aStreamEvent.Task)
            assertEquals(A2aStreamEvent.Completed, events.last())
            assertTrue(usedResubscribe.get())
        }
    }

    @Test
    fun malformedCardAndSseAreProtocolFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json"))
        client().use { client ->
            val card = client.getAgentCard(remote()) as A2aResult.Failure
            assertTrue(card.error is A2aClientError.Protocol)
        }

        server.dispatcher = sseDispatcher { "data: not-json\n\n" }
        client().use { client ->
            val events = client.streamMessage(remote(), messageRequest()).toList()
            assertTrue(events.single() is A2aStreamEvent.Failed)
            assertTrue((events.single() as A2aStreamEvent.Failed).error is A2aClientError.Protocol)
        }
    }

    @Test
    fun duplicateStreamEventFailsExplicitlyWithoutCompleted() = runBlocking {
        server.dispatcher = sseDispatcher { id ->
            val event = rpcEnvelope(id, """{"message":{"messageId":"m2","role":"agent","parts":[{"kind":"text","text":"same"}]}}""")
            "data: $event\n\ndata: $event\n\n"
        }
        client().use { client ->
            val events = client.streamMessage(remote(), messageRequest()).toList()
            assertTrue(events.first() is A2aStreamEvent.Message)
            val failure = events.last() as A2aStreamEvent.Failed
            assertEquals(A2aClientError.Protocol("duplicate_stream_event"), failure.error)
            assertFalse(events.contains(A2aStreamEvent.Completed))
        }
    }

    @Test
    fun callTimeoutMapsToStableTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        client(requestTimeoutMillis = 100).use { client ->
            val result = client.sendMessage(remote(), messageRequest()) as A2aResult.Failure
            assertTrue(result.error is A2aClientError.Timeout)
        }
    }

    @Test
    fun authValuesNeverAppearInErrorsAndReservedHeadersAreRejected() = runBlocking {
        val secret = "secret-canary-value"
        server.enqueue(MockResponse().setResponseCode(401).setBody(secret))
        val auth = A2aAuthProvider {
            A2aProtectedHeaders.of(mapOf("Authorization" to "Bearer $secret"))
        }
        client(authProvider = auth).use { client ->
            val result = client.sendMessage(remote(), messageRequest()) as A2aResult.Failure
            assertTrue(result.error is A2aClientError.Authentication)
            assertFalse(result.error.toString().contains(secret))
            assertEquals("Bearer $secret", server.takeRequest().getHeader("Authorization"))
        }

        val conflicting = A2aAuthProvider {
            A2aProtectedHeaders.of(mapOf("Content-Type" to secret))
        }
        client(authProvider = conflicting).use { client ->
            val result = client.sendMessage(remote(), messageRequest()) as A2aResult.Failure
            assertEquals(A2aClientError.Configuration("reserved_header_conflict"), result.error)
            assertFalse(result.error.toString().contains(secret))
        }
    }

    @Test
    fun coroutineCancellationCancelsBlockedHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        client().use { client ->
            val operation = async { client.sendMessage(remote(), messageRequest()) }
            server.takeRequest(2, TimeUnit.SECONDS)
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
        }
    }

    private fun client(
        authProvider: A2aAuthProvider = A2aAuthProvider.None,
        requestTimeoutMillis: Long = 60_000,
    ): A2aClient =
        DirectA2aClientModule().factory().create(
            A2aClientConfig(
                authProvider = authProvider,
                allowLoopbackHttpForDebug = true,
                requestTimeout = requestTimeoutMillis.milliseconds,
            ),
        )

    private fun remote(): RemoteAgentRef = RemoteAgentRef(
        id = "weather",
        endpoint = URI(server.url("/rpc").toString()),
    )

    private suspend fun discoverCardAdvertising(url: String): A2aResult<*> {
        server.enqueue(MockResponse().setBody("""{"name":"weather","url":"$url"}"""))
        return client().use { client -> client.getAgentCard(remote()) }
    }

    private fun messageRequest(): A2aSendMessageRequest = A2aSendMessageRequest(
        A2aMessage(
            messageId = "m1",
            role = A2aMessage.Role.USER,
            parts = listOf(A2aPart.Text("ping")),
        ),
    )

    private fun taskJson(state: String): String =
        """{"id":"t1","contextId":"c1","status":{"state":"$state"}}"""

    private fun rpcEnvelope(id: String, result: String): String =
        """{"jsonrpc":"2.0","id":"$id","result":$result}"""

    private fun rpcId(body: String): String =
        Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("request id missing")

    private fun rpcDispatcher(result: (RecordedRequest) -> String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.clone().readUtf8()
            return MockResponse().setBody(rpcEnvelope(rpcId(body), result(request)))
        }
    }

    private fun sseDispatcher(body: (String) -> String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val id = rpcId(request.body.readUtf8())
            return MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body(id), 7)
        }
    }
}
