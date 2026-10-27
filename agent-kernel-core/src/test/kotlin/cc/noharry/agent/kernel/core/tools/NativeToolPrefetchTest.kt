@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeToolPrefetchTest {
    @Test
    fun onDemandSelectorKeepsLocalHintsWithoutWaitingForRemoteBeforeModel() = runTest {
        val provider = Selector { _, _ -> error("remote must wait for search_tools") }
        provider.prefetchOnSearch = true
        provider.localNames = listOf("route")
        provider.catalog = provider.catalog + ToolDescriptor("audio", "music", "{}")
        val store = ToolActivationStore()
        val recorder = Recording()
        prefetchRuntimeTools("navigate then play", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertEquals(listOf("route"), store.activated("r").map { it.originalToolName })
        assertEquals(1, recorder.started.size)
    }

    private val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

    @Test
    fun selectsOnlyCurrentProviderNamesAndRetainsDescriptionAndTrace() = runTest {
        val recorder = Recording()
        val provider = Selector { request, trace ->
            assertEquals("complex request", request.input)
            assertEquals(listOf("route"), request.tools.map { it.name })
            trace.request("""{"state":"complex request"}""")
            trace.response("""{"score":0.9}""")
            listOf("route", "invented", "mcp__other__route", "route")
        }
        val store = ToolActivationStore()
        prefetchRuntimeTools("complex request", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        val activated = store.activated("r")
        assertEquals(1, activated.size)
        assertEquals("route", activated.single().originalToolName)
        assertTrue(activated.single().schemaJson.contains("trusted route description"))
        assertEquals("tool_prefetch", activated.single().metadata.source)
        assertTrue(recorder.started.single().argumentsJson.contains("complex request"))
        assertTrue(recorder.finished.single().contains("\"score\":0.9"))
        assertFalse(recorder.finished.single().contains("invented"))
    }

    @Test
    fun ordinaryProviderDoesNotReadCatalogOrCreatePrefetchEvents() = runTest {
        var reads = 0
        val provider = object : ToolProvider {
            override val providerId = "plain"
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override suspend fun listTools(): List<ToolDescriptor> { reads++; return emptyList() }
            override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("not called")
        }
        val recorder = Recording()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), ToolActivationStore(), recorder)
        assertEquals(0, reads)
        assertTrue(recorder.started.isEmpty())
    }

    @Test
    fun selectorFailureIsAdvisoryAndLeavesSearchCatalogAvailable() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); error("failure") }
        val registry = ToolProviderRegistry(listOf(provider))
        val store = ToolActivationStore()
        val recorder = Recording()
        prefetchRuntimeTools("input", context, registry, store, recorder)
        assertTrue(store.activated("r").isEmpty())
        assertEquals(1, registry.allTools(context).size)
        assertTrue(recorder.finished.single().contains("FAILED"))
    }

    @Test
    fun localHintsSurviveRemoteTimeoutAndHaveDistinctCanonicalCallIds() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); delay(10_000); emptyList() }
        // One catalog entry stays unactivated so the remote round still runs and times out.
        provider.catalog = provider.catalog + ToolDescriptor("mapzoom", "zoom the map", "{}")
        provider.localNames = listOf("route", "unknown", "route")
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertEquals(listOf("route"), store.activated("r").map { it.originalToolName })
        assertEquals(2, recorder.started.map { it.id }.distinct().size)
        assertTrue(recorder.started.first().argumentsJson.contains("local_tool_hints"))
        assertTrue(recorder.finished.first().contains("\"status\":\"OK\""))
        assertTrue(recorder.finished.last().contains("TIMEOUT"))
    }

    @Test
    fun remoteRoundOnlyRanksToolsLocalHintsDidNotActivate() = runTest {
        val offered = mutableListOf<List<String>>()
        val provider = Selector { request, _ -> offered += request.tools.map { it.name }; listOf("mapzoom") }
        provider.catalog = listOf(
            ToolDescriptor("route", "trusted route description", "{}"),
            ToolDescriptor("mapzoom", "zoom the map", "{}"),
        )
        provider.localNames = listOf("route")
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, Recording())
        assertEquals(listOf(listOf("mapzoom")), offered)
        assertEquals(setOf("route", "mapzoom"), store.activated("r").map { it.originalToolName }.toSet())
    }

    @Test
    fun remoteRoundIsSkippedWhenLocalHintsAlreadyCoverTheCatalog() = runTest {
        val provider = Selector { _, _ -> error("remote must not rank an already activated catalog") }
        provider.catalog = listOf(ToolDescriptor("route", "trusted route description", "{}"))
        provider.localNames = listOf("route")
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertEquals(listOf("route"), store.activated("r").map { it.originalToolName })
        assertEquals(1, recorder.started.size)
    }

    @Test
    fun localOnlyHintsDoNotCallRemoteAndStayWithinGlobalLimit() = runTest {
        val provider = Selector { _, _ -> error("must not call remote") }
        provider.remotePrefetchEnabled = false
        provider.catalog = (1..40).map { ToolDescriptor("tool_$it", "fixture", "{}") }
        provider.localNames = provider.catalog.map { it.name }
        val store = ToolActivationStore()
        val recorder = Recording()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertEquals(24, store.activated("r").size)
        assertEquals(1, recorder.started.size)
    }

    @Test
    fun disabledLocalOnlyProviderEmitsNoSyntheticDiscovery() = runTest {
        val provider = Selector { _, _ -> error("disabled") }
        provider.remotePrefetchEnabled = false
        val recorder = Recording()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), ToolActivationStore(), recorder)
        assertTrue(recorder.started.isEmpty())
    }

    @Test
    fun deadlineFallsBackWithoutActivation() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); delay(10_000); listOf("route") }
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertTrue(store.activated("r").isEmpty())
        assertTrue(recorder.finished.single().contains("TIMEOUT"))
    }

    @Test
    fun configuredLongerDeadlineCanActivateAfterFiveSeconds() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); delay(12_000); listOf("route") }
        provider.prefetchTimeoutMillis = 15_000
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, Recording())
        assertEquals("route", store.activated("r").single().originalToolName)
    }

    @Test
    fun excessiveProviderDeadlineIsCappedByKernelBudget() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); delay(16_000); listOf("route") }
        provider.prefetchTimeoutMillis = 30_000
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertTrue(store.activated("r").isEmpty())
        assertTrue(recorder.finished.single().contains("TIMEOUT"))
    }

    @Test
    fun sequentialStagesKeepBothBodiesAndDistinctCallIds() = runTest {
        val provider = Selector { _, trace ->
            trace.request("""{"stage":"domains"}""")
            trace.diagnostics("""{"connectionId":"c1","connectionReused":false}""")
            trace.response("""{"domains":["navigation"]}""")
            trace.request("""{"stage":"tools"}""")
            trace.response("""{"tools":["route"]}""")
            listOf("route")
        }
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertEquals(2, recorder.started.map { it.id }.distinct().size)
        assertTrue(recorder.finished[0].contains("\"domains\":[\"navigation\"]"))
        assertTrue(recorder.finished[1].contains("\"tools\":[\"route\"]"))
        assertTrue(recorder.finished[0].contains("\"stage\":1"))
        assertTrue(recorder.finished[1].contains("\"stage\":2"))
        assertTrue(recorder.finished[0].contains("\"diagnostics\":{\"connectionId\":\"c1\",\"connectionReused\":false}"))
        assertFalse(recorder.finished[1].contains("diagnostics"))
        assertEquals(1, store.activated("r").size)
    }

    @Test
    fun twoStagesShareOneProviderDeadlineAndDoNotActivatePartialResults() = runTest {
        val provider = Selector { _, trace ->
            trace.request("""{"stage":"domains"}""")
            delay(2_000)
            trace.response("""{"domains":["navigation"]}""")
            trace.request("""{"stage":"tools"}""")
            try { delay(2_000) } finally { trace.diagnostics("""{"transportFinished":false}""") }
            listOf("route")
        }
        val recorder = Recording()
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, recorder)
        assertTrue(recorder.finished[0].contains("\"status\":\"OK\""))
        assertTrue(recorder.finished[1].contains("\"status\":\"TIMEOUT\""))
        assertTrue(recorder.finished[1].contains("\"diagnostics\":{\"transportFinished\":false}"))
        assertTrue(store.activated("r").isEmpty())
    }

    @Test
    fun parentCancellationPropagatesAndRecordsTerminalDiscovery() = runTest {
        val provider = Selector { _, trace -> trace.request("{}"); throw CancellationException("cancel") }
        val recorder = Recording()
        try {
            prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), ToolActivationStore(), recorder)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(recorder.finished.single().contains("CANCELLED"))
        }
    }

    @Test
    fun activationIsBoundedDespiteSelectorReturningWholeCatalog() = runTest {
        val provider = Selector { request, trace -> trace.request("{}"); request.tools.map { it.name } }
        provider.catalog = (1..40).map { ToolDescriptor("tool_$it", "tool", "{}") }
        val store = ToolActivationStore()
        prefetchRuntimeTools("input", context, ToolProviderRegistry(listOf(provider)), store, Recording())
        assertEquals(24, store.activated("r").size)
    }
}

private class Selector(
    val select: suspend (ToolPrefetchRequest, ToolPrefetchTrace) -> List<String>,
) : ToolProvider, ToolPrefetchProvider {
    override val providerId = "selector"
    override var prefetchTimeoutMillis = 3_000L
    override var remotePrefetchEnabled = true
    override var prefetchOnSearch = false
    var localNames = emptyList<String>()
    override fun localToolNames(request: ToolPrefetchRequest) = localNames
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    var catalog = listOf(ToolDescriptor("route", "trusted route description", "{}"))
    override suspend fun listTools() = catalog
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("prefetch must not execute")
    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace) = select(request, trace)
}

private class Recording : LoopSessionRecorder by LoopSessionRecorder.noop() {
    val started = mutableListOf<ModelEvent.ToolCall>()
    val finished = mutableListOf<String>()
    override fun recordToolStarted(context: MaterializerContext, event: ModelEvent.ToolCall, source: DynamicToolSource?) {
        started += event
    }
    override fun recordToolFinished(
        context: MaterializerContext, event: ModelEvent.ToolCall, source: DynamicToolSource?,
        resultJson: String, telemetry: WorkspaceExecutionTelemetry,
    ) { finished += resultJson }
}
