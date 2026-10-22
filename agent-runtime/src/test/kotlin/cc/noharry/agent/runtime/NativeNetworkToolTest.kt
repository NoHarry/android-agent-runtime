@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.RuntimeHookRegistration
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.transport.NetworkAddressDeniedException
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.*

class NativeNetworkToolTest {
    @Test
    fun webFetchIsAvailableAsNativeNetworkToolInFirstRound() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val loop = networkLoop(
            transport = RecordingWebFetchTransport(body = "hello"),
            seenRequests = seenRequests,
        )

        loop.run(agentRequest(), runId = "network-run").events()

        assertTrue(seenRequests.first().tools.any { it.name == "web_fetch" })
    }

    @Test
    fun webFetchUsesBoundedTransportAndFeedsTruncatedResultBackToModel() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val transport = RecordingWebFetchTransport(body = "hello world")
        val loop = networkLoop(
            transport = transport,
            seenRequests = seenRequests,
        )

        val waiting = loop.run(agentRequest(), runId = "network-run") as NativeLoopOutcome.RequiresConfirmation
        assertTrue(waiting.prompt.contains("example.com"))
        val resumed = loop.resumeConfirmedToolUse(requireNotNull(waiting.pendingToolUse)) as NativeLoopOutcome.Terminal
        val result = resumed.result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(listOf("https://example.com/page"), transport.requestUrls)
        assertEquals(EXPECTED_WEB_FETCH_TRANSFER_CAP_BYTES, transport.requests.single().maxTransferBytes)
        val toolResult = seenRequests[1].messages.last().content
        assertTrue(toolResult.contains(""""statusCode":200"""))
        assertTrue(toolResult.contains(""""truncated":true"""))
        assertTrue(toolResult.contains("hello"))
        assertFalse(toolResult.contains("world"))
    }

    @Test
    fun webFetchRejectsLoopbackBeforeTransport() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val transport = RecordingWebFetchTransport(body = "blocked")
        val loop = networkLoop(
            transport = transport,
            seenRequests = seenRequests,
            fetchUrl = "http://127.0.0.1/private",
        )

        val events = loop.run(agentRequest(), runId = "network-run").events()

        assertEquals(emptyList<String>(), transport.requestUrls)
        assertTrue(seenRequests[1].messages.last().content.contains(RuntimeErrorCode.NETWORK_PRIVATE_ADDRESS_DENIED.wireCode))
        val activity = events.networkActivity()
        assertEquals("web_fetch", activity.toolName)
        assertEquals("network-1", activity.toolCallId)
        assertEquals("127.0.0.1", activity.domain)
        assertEquals("policy_denied", activity.decision)
        assertEquals(null, activity.statusCode)
        assertFalse(activity.truncated)
    }

    @Test
    fun webFetchMapsDeniedDnsResolutionToPrivateAddressError() = runBlocking {
        val backend = NetworkToolBackend(
            transport = WebFetchTransport { throw NetworkAddressDeniedException("example.com") },
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/private"}""")

        assertTrue(result.contains(RuntimeErrorCode.NETWORK_PRIVATE_ADDRESS_DENIED.wireCode))
        assertFalse(result.contains(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode))
    }

    @Test
    fun webFetchFollowsSameHostRelativeRedirect() = runBlocking {
        val transport = RecordingWebFetchTransport { request ->
            when (request.url) {
                "https://example.com/start" -> redirectResponse("/final")
                "https://example.com/final" -> textResponse("redirect complete")
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/start"}""")

        assertEquals(listOf("https://example.com/start", "https://example.com/final"), transport.requestUrls)
        assertTrue(result.contains("redirect complete"))
    }

    @Test
    fun webFetchCanonicalizesHostAndRejectsUserInfo() = runBlocking {
        val transport = RecordingWebFetchTransport(body = "ok")
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val canonical = backend.execute("web_fetch", """{"url":"https://EXAMPLE.com./page#fragment"}""")
        val userInfo = backend.execute("web_fetch", """{"url":"https://user:pass@example.com/private"}""")

        assertTrue(canonical.contains(""""ok":true"""))
        assertEquals(listOf("https://example.com/page"), transport.requestUrls)
        assertTrue(userInfo.contains(RuntimeErrorCode.NETWORK_URL_INVALID.wireCode))
    }

    @Test
    fun webFetchRejectsCrossHostRedirectWithoutConnectingTarget() = runBlocking {
        val transport = RecordingWebFetchTransport { redirectResponse("https://example.org/private") }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/start"}""")

        assertEquals(listOf("https://example.com/start"), transport.requestUrls)
        assertTrue(result.contains(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode))
        assertTrue(result.contains("Cross-host redirect"))
    }

    @Test
    fun webFetchRejectsPrivateRedirectBeforeConnectingTarget() = runBlocking {
        val transport = RecordingWebFetchTransport { redirectResponse("https://127.0.0.1/private") }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/start"}""")

        assertEquals(listOf("https://example.com/start"), transport.requestUrls)
        assertTrue(result.contains(RuntimeErrorCode.NETWORK_PRIVATE_ADDRESS_DENIED.wireCode))
    }

    @Test
    fun webFetchRejectsHttpsDowngradeWithoutSecondRequest() = runBlocking {
        val transport = RecordingWebFetchTransport { redirectResponse("http://example.com/plain") }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/start"}""")

        assertEquals(listOf("https://example.com/start"), transport.requestUrls)
        assertTrue(result.contains(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode))
        assertTrue(result.contains("HTTPS to HTTP"))
    }

    @Test
    fun webFetchStopsAtRedirectLimit() = runBlocking {
        val transport = RecordingWebFetchTransport { redirectResponse("/loop") }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/loop"}""")

        assertEquals(6, transport.requestUrls.size)
        assertTrue(result.contains(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode))
        assertTrue(result.contains("Redirect limit exceeded"))
    }

    @Test
    fun webFetchReportsRawTransferCapAsTruncation() = runBlocking {
        val transport = RecordingWebFetchTransport {
            textResponse("bounded prefix").copy(transferTruncated = true)
        }
        val backend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = backend.execute("web_fetch", """{"url":"https://example.com/large"}""")

        assertTrue(result.contains(""""truncated":true"""))
        assertTrue(result.contains("bounded prefix"))
    }

    @Test
    fun webFetchPropagatesCancellation() = runBlocking {
        val backend = NetworkToolBackend(
            transport = WebFetchTransport { throw CancellationException("cancel web fetch") },
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        var cancellation: CancellationException? = null
        try {
            backend.execute("web_fetch", """{"url":"https://example.com/page"}""")
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("cancel web fetch", cancellation?.message)
    }

    @Test
    fun webFetchIsVisibleToHooksAsNetworkSource() = runBlocking {
        val seenSources = mutableListOf<ToolSource>()
        val loop = networkLoop(
            transport = RecordingWebFetchTransport(body = "hello"),
            seenRequests = mutableListOf(),
            hookChain = RuntimeHookChain(
                listOf(
                    RuntimeHookRegistration(
                        hook = object : RuntimeHook {
                            override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
                                if (call.toolName == "web_fetch") seenSources += call.source
                                return HookDecision.Allow()
                            }
                        },
                    ),
                ),
            ),
        )

        loop.run(agentRequest(), runId = "network-run").events()

        assertEquals(listOf(ToolSource.NETWORK), seenSources)
    }

    @Test
    fun webFetchHookDenialEmitsNetworkActivityWithoutRawUrl() = runBlocking {
        val loop = networkLoop(
            transport = RecordingWebFetchTransport(body = "hello"),
            seenRequests = mutableListOf(),
            hookChain = RuntimeHookChain(
                listOf(
                    RuntimeHookRegistration(
                        hook = object : RuntimeHook {
                            override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
                                HookDecision.Deny(RuntimeErrorCode.HOOK_DENIED.wireCode, "blocked")
                        },
                    ),
                ),
            ),
        )

        val events = loop.run(agentRequest(), runId = "network-run").events()

        val activity = events.networkActivity()
        assertEquals("example.com", activity.domain)
        assertEquals("hook_denied", activity.decision)
        assertFalse(activity.domain.contains("/page"))
    }

    @Test
    fun webFetchRequiresConfirmationForNewPublicDomainBeforeTransport() = runBlocking {
        val transport = RecordingWebFetchTransport(body = "hello")
        val loop = networkLoop(
            transport = transport,
            seenRequests = mutableListOf(),
        )

        val waiting = loop.run(agentRequest(), runId = "network-run") as NativeLoopOutcome.RequiresConfirmation

        assertTrue(waiting.prompt.contains("example.com"))
        assertEquals(emptyList<String>(), transport.requestUrls)
        val activity = waiting.events.networkActivity()
        assertEquals("network-run", activity.runId)
        assertEquals("web_fetch", activity.toolName)
        assertEquals("network-1", activity.toolCallId)
        assertEquals("example.com", activity.domain)
        assertEquals("confirmation_required", activity.decision)
        assertEquals(null, activity.statusCode)
        assertFalse(activity.truncated)
    }

    @Test
    fun webFetchAllowAllHostsPolicySkipsConfirmationAndFetchesDirectly() = runBlocking {
        val transport = RecordingWebFetchTransport(body = "hello")
        val seenRequests = mutableListOf<ModelRequest>()
        val loop = networkLoop(
            transport = transport,
            seenRequests = seenRequests,
            networkToolBackend = NetworkToolBackend(transport, NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS),
        )

        val terminal = loop.run(agentRequest(), runId = "allow-all-run") as NativeLoopOutcome.Terminal

        assertEquals(RuntimeRunStatus.COMPLETED, terminal.result.status)
        // No RequiresConfirmation pause: transport was hit in the first round.
        assertEquals(listOf("https://example.com/page"), transport.requestUrls)
        val activity = terminal.events.networkActivity()
        assertEquals("web_fetch", activity.toolName)
        assertEquals("example.com", activity.domain)
        assertEquals("allowed", activity.decision)
    }

    @Test
    fun nativeWebFetchDisabledOmitsWebFetchFromToolset() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(
                        ModelEvent.ToolCall(
                            id = "finish-1",
                            name = "finish",
                            argumentsJson = """{"response":"no web_fetch"}""",
                        ),
                        ModelEvent.Completed,
                    ),
                ),
                seenRequests = seenRequests,
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            nativeWebFetchEnabled = false,
        )

        loop.run(agentRequest(), runId = "no-web-fetch-run")

        assertFalse(seenRequests.first().tools.any { it.name == "web_fetch" })
    }

    @Test
    fun resumeConfirmedWebFetchEmitsPublicToolAndNetworkActivityEvents() = runBlocking {
        val emitted = mutableListOf<AgentEvent>()
        val loop = networkLoop(
            transport = RecordingWebFetchTransport(body = "hello"),
            seenRequests = mutableListOf(),
        )

        val waiting = loop.run(agentRequest(), runId = "network-run") as NativeLoopOutcome.RequiresConfirmation
        loop.resumeConfirmedToolUse(requireNotNull(waiting.pendingToolUse)) { emitted += it }
            as NativeLoopOutcome.Terminal

        assertTrue(emitted.any {
            it is AgentEvent.NativeToolCallFinished &&
                it.toolCallId == "network-1" &&
                it.toolName == "web_fetch"
        })
        val allowed = emitted.filterIsInstance<AgentEvent.NetworkToolActivity>()
            .single { it.decision == "allowed" }
        assertEquals("example.com", allowed.domain)
        assertEquals(200, allowed.statusCode)
    }

    @Test
    fun resumeConfirmedWebFetchCanPauseAgainForSecondNewDomain() = runBlocking {
        val transport = RecordingWebFetchTransport(body = "hello")
        val loop = networkLoopForFetchUrls(
            urls = listOf("https://example.com/page", "https://example.org/page"),
            transport = transport,
        )

        val first = loop.run(agentRequest(), runId = "network-run") as NativeLoopOutcome.RequiresConfirmation
        val second = loop.resumeConfirmedToolUse(requireNotNull(first.pendingToolUse)) as NativeLoopOutcome.RequiresConfirmation

        assertTrue(second.prompt.contains("example.org"))
        assertEquals(listOf("https://example.com/page"), transport.requestUrls)

        val terminal = loop.resumeConfirmedToolUse(requireNotNull(second.pendingToolUse)) as NativeLoopOutcome.Terminal
        assertEquals(RuntimeRunStatus.COMPLETED, terminal.result.status)
        assertEquals(listOf("https://example.com/page", "https://example.org/page"), transport.requestUrls)
    }

    @Test
    fun resumeConfirmedWebFetchAllowsSameDomainAgainInRun() = runBlocking {
        val transport = RecordingWebFetchTransport(body = "hello")
        val loop = networkLoopForFetchUrls(
            urls = listOf("https://example.com/first", "https://example.com/second"),
            transport = transport,
        )

        val waiting = loop.run(agentRequest(), runId = "network-run") as NativeLoopOutcome.RequiresConfirmation
        val terminal = loop.resumeConfirmedToolUse(requireNotNull(waiting.pendingToolUse)) as NativeLoopOutcome.Terminal

        assertEquals(RuntimeRunStatus.COMPLETED, terminal.result.status)
        assertEquals(listOf("https://example.com/first", "https://example.com/second"), transport.requestUrls)
    }

    @Test
    fun hostedSearchActivityEmitsMetadataOnlyPublicEvent() = runBlocking {
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                listOf(
                    listOf(
                        ModelEvent.SearchActivity(provider = "OpenAI", queryPreview = "latest sdk", resultCount = 2),
                        ModelEvent.ToolCall(
                            id = "finish-1",
                            name = "finish",
                            argumentsJson = """{"response":"done"}""",
                        ),
                        ModelEvent.Completed,
                    ),
                ),
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
        )

        val terminal = loop.run(agentRequest(), runId = "search-run") as NativeLoopOutcome.Terminal
        val search = terminal.events.filterIsInstance<AgentEvent.SearchActivity>().single()

        assertEquals("OpenAI", search.provider)
        assertEquals("latest sdk", search.queryPreview)
        assertEquals(2, search.resultCount)
    }

    private fun networkLoop(
        transport: WebFetchTransport,
        seenRequests: MutableList<ModelRequest>,
        fetchUrl: String = "https://example.com/page",
        hookChain: RuntimeHookChain = RuntimeHookChain.empty(),
        networkToolBackend: NetworkToolBackend = NetworkToolBackend(transport),
    ): NativeToolLoop =
        NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(
                        ModelEvent.ToolCall(
                            id = "network-1",
                            name = "web_fetch",
                            argumentsJson = """{"url":"$fetchUrl","max_bytes":5}""",
                        ),
                        ModelEvent.Completed,
                    ),
                    listOf(
                        ModelEvent.ToolCall(
                            id = "finish-1",
                            name = "finish",
                            argumentsJson = """{"response":"network complete"}""",
                        ),
                        ModelEvent.Completed,
                    ),
                ),
                seenRequests = seenRequests,
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            hookChain = hookChain,
            networkToolBackend = networkToolBackend,
        )

    private fun networkLoopForFetchUrls(
        urls: List<String>,
        transport: WebFetchTransport,
    ): NativeToolLoop {
        val rounds = urls.mapIndexed { index, url ->
            listOf(
                ModelEvent.ToolCall(
                    id = "network-${index + 1}",
                    name = "web_fetch",
                    argumentsJson = """{"url":"$url","max_bytes":5}""",
                ),
                ModelEvent.Completed,
            )
        } + listOf(
            listOf(
                ModelEvent.ToolCall(
                    id = "finish-1",
                    name = "finish",
                    argumentsJson = """{"response":"network complete"}""",
                ),
                ModelEvent.Completed,
            ),
        )
        return NativeToolLoop(
            modelClient = FakeModelClient(eventsByRound = rounds),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            networkToolBackend = NetworkToolBackend(transport),
        )
    }
}

private class RecordingWebFetchTransport(
    private val responseFor: (WebFetchRequest) -> WebFetchResponse,
) : WebFetchTransport {
    constructor(body: String) : this(responseFor = { textResponse(body) })

    val requests = mutableListOf<WebFetchRequest>()
    val requestUrls: List<String>
        get() = requests.map { it.url }

    override suspend fun execute(request: WebFetchRequest): WebFetchResponse {
        requests += request
        return responseFor(request)
    }
}

private fun textResponse(body: String): WebFetchResponse =
    WebFetchResponse(
        statusCode = 200,
        headers = mapOf("content-type" to listOf("text/plain; charset=utf-8")),
        body = body.toByteArray(),
    )

private fun redirectResponse(location: String): WebFetchResponse =
    WebFetchResponse(
        statusCode = 302,
        headers = mapOf("location" to listOf(location)),
    )

private fun NativeLoopOutcome.events(): List<AgentEvent> =
    when (this) {
        is NativeLoopOutcome.Terminal -> events
        is NativeLoopOutcome.RequiresConfirmation -> events
        is NativeLoopOutcome.NeedsUserInput -> events
    }

private fun List<AgentEvent>.networkActivity(): AgentEvent.NetworkToolActivity =
    filterIsInstance<AgentEvent.NetworkToolActivity>().single()

private const val EXPECTED_WEB_FETCH_TRANSFER_CAP_BYTES = 512 * 1024
