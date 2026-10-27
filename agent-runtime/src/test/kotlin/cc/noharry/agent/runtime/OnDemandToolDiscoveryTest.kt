@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import cc.noharry.agent.vfs.VfsFileSystem
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OnDemandToolDiscoveryTest {
    private val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

    @Test fun remoteSelectionActivatesMissingToolAndPreservesLocalFallback() = runBlocking {
        val provider = SearchSelector()
        val store = ToolActivationStore()
        val recorder = SearchRecording()
        val workspace = workspace(provider, store, recorder)
        val result = workspace.executeJson("search_tools", """{"query":"something missing"}""", context)
        assertEquals(listOf("something missing"), provider.queries)
        assertTrue(result.contains("mcp__selector__read_route"))
        assertTrue(store.activated("r").any { it.originalToolName == "read_route" })
        assertEquals(1, recorder.started.size)
        assertTrue(recorder.finished.single().contains("selectedTools"))
        assertEquals(0, provider.businessCalls)
    }

    @Test fun exactNamesAndAlreadyActivatedCatalogDoNotSpendRemoteCalls() = runBlocking {
        val provider = SearchSelector()
        val store = ToolActivationStore()
        val workspace = workspace(provider, store)
        workspace.executeJson("search_tools", """{"query":"read_route"}""", context)
        workspace.executeJson("search_tools", """{"query":"route"}""", context)
        assertTrue(provider.queries.isEmpty())
    }

    @Test fun timeoutFallsBackToKeywordSearchAndRetainsFailureEvidence() = runBlocking {
        val provider = SearchSelector(wait = true)
        val store = ToolActivationStore()
        val recorder = SearchRecording()
        val result = workspace(provider, store, recorder).executeJson("search_tools", """{"query":"route"}""", context)
        assertTrue(result.contains("read_route"))
        assertTrue(recorder.finished.single().contains("TIMEOUT"))
        assertEquals(1, store.activated("r").size)
    }

    @Test fun repeatedSearchesHaveDistinctTraceIdsAndMalformedQueriesDoNotCallRemote() = runBlocking {
        val provider = SearchSelector(selection = emptyList())
        val recorder = SearchRecording()
        val workspace = workspace(provider, ToolActivationStore(), recorder)
        repeat(2) { workspace.executeJson("search_tools", """{"query":"missing-$it"}""", context) }
        workspace.executeJson("search_tools", """{"query":""}""", context)
        assertEquals(2, recorder.started.map { it.id }.distinct().size)
        assertEquals(2, provider.queries.size)
    }

    @Test fun duplicateQueriesUseCacheAndPerRunBudgetFallsBackLocally() = runBlocking {
        val provider = SearchSelector(selection = emptyList())
        val workspace = workspace(provider, ToolActivationStore())
        repeat(2) { workspace.executeJson("search_tools", """{"query":"missing"}""", context) }
        assertEquals(1, provider.queries.size)
        repeat(4) { workspace.executeJson("search_tools", """{"query":"missing-$it"}""", context) }
        val fallback = workspace.executeJson("search_tools", """{"query":"route"}""", context)
        assertEquals(3, provider.queries.size)
        assertTrue(fallback.contains("read_route"))
        workspace.executeJson("search_tools", """{"query":"missing"}""", context.copy(runId = "another"))
        assertEquals(4, provider.queries.size)
    }

    @Test fun resumedWorkspaceKeepsTheSameRunDiscoveryBudget() = runBlocking {
        val provider = SearchSelector(selection = emptyList())
        val discovery = OnDemandToolDiscovery()
        val before = workspace(provider, ToolActivationStore(), discovery = discovery)
        repeat(3) { before.executeJson("search_tools", """{"query":"missing-$it"}""", context) }
        val resumed = workspace(provider, ToolActivationStore(), discovery = discovery)
        resumed.executeJson("search_tools", """{"query":"another missing capability"}""", context)
        assertEquals(3, provider.queries.size)
    }

    private fun workspace(provider: SearchSelector, store: ToolActivationStore,
        recorder: LoopSessionRecorder = LoopSessionRecorder.noop(), discovery: OnDemandToolDiscovery = OnDemandToolDiscovery()) =
        VirtualWorkspace(VfsFileSystem.singleFileRoot(Files.createTempDirectory("on-demand-").toFile()),
            mcpRegistry = ToolProviderRegistry(listOf(provider)), activationStore = store, sessionRecorder = recorder, onDemandDiscovery = discovery)
}

private class SearchSelector(private val wait: Boolean = false, private val selection: List<String> = listOf("read_route", "invented")) : ToolProvider, ToolPrefetchProvider {
    override val providerId = "selector"
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override val prefetchOnSearch = true
    override val prefetchTimeoutMillis = 100L
    val queries = mutableListOf<String>()
    var businessCalls = 0
    override suspend fun listTools() = listOf(ToolDescriptor("read_route", "route readout", """{"type":"object"}"""))
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        businessCalls++
        error("discovery does not execute business tools")
    }
    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> {
        queries += request.input
        trace.request("{}")
        if (wait) delay(1_000)
        trace.response("{}")
        return selection
    }
}

private class SearchRecording : LoopSessionRecorder by LoopSessionRecorder.noop() {
    val started = mutableListOf<ModelEvent.ToolCall>()
    val finished = mutableListOf<String>()
    override fun recordToolStarted(context: MaterializerContext, event: ModelEvent.ToolCall, source: DynamicToolSource?) { started += event }
    override fun recordToolFinished(context: MaterializerContext, event: ModelEvent.ToolCall, source: DynamicToolSource?, resultJson: String, telemetry: WorkspaceExecutionTelemetry) { finished += resultJson }
}
