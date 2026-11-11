@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.console
import cc.noharry.agent.appserver.runtime.runRequest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.evidence.SAMPLE_DEBUG_CASE_PACK_PROMPT
import cc.noharry.agent.appserver.evidence.SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256
import cc.noharry.agent.appserver.evidence.agentReliabilityRoot
import cc.noharry.agent.appserver.evidence.appDiagnostics
import cc.noharry.agent.appserver.evidence.productionCaseBoundDecision
import cc.noharry.agent.appserver.evidence.sha256HexUtf8
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.DEBUG_SESSION_ID
import cc.noharry.agent.appserver.runtime.PreviousExchange
import cc.noharry.agent.appserver.runtime.agentRequest
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.buildRuntime
import cc.noharry.agent.appserver.runtime.previousExchange
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.TranscriptStore
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.SessionEventLogDurability
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.runtime.RuntimeInspection.RUN_CONTEXT_HEADER
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral production path: exercises [buildRuntime] the same way
 * [RuntimeSessionRunner] does — decision from request text, same bytes as
 * [RuntimeTurnRequest.input], simulated model finish-only run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProductionBuilderPathTest {

    @Test
    fun ordinaryPromptDoesNotWriteProbeArtifacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = agentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = "finish with done ordinary demo"
        val runtime = productionBuildRuntime(context, text)
        runtime.runRequest(productionRequest(text, "ord-1")).toList()

        assertFalse(
            "ordinary prompt must not create agent-reliability probe runs",
            hasProbeArtifacts(reliability),
        )
    }

    @Test
    fun exactBenchPromptBindsCaseAndPromptHashViaProductionBuilder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = agentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = SAMPLE_DEBUG_CASE_PACK_PROMPT
        assertTrue(productionCaseBoundDecision(text))
        val runtime = productionBuildRuntime(context, text)
        val request = productionRequest(text, "bench-1")
        assertTrue(request.input == text)
        assertTrue(request.input == SAMPLE_DEBUG_CASE_PACK_PROMPT)
        runtime.runRequest(request).toList()

        val runDir = findProbeRunDir(reliability)
        val probes = File(runDir, "probes.jsonl").readText()
        val meta = File(runDir, "run-meta.json").readText()
        assertTrue(probes.contains("case.bound"))
        assertTrue(meta.contains("sample.debug.v1"))
        assertTrue(meta.contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
        assertTrue(meta.contains("bench_core"))
        // Stored hash must equal hash of actual request input bytes.
        assertTrue(sha256HexUtf8(request.input) == SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
    }

    @Test
    fun whitespacePaddedBenchPromptDoesNotBindViaProductionBuilder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = agentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "
        assertFalse(productionCaseBoundDecision(text))
        val runtime = productionBuildRuntime(context, text)
        val request = productionRequest(text, "pad-1")
        assertTrue(request.input == text)
        runtime.runRequest(request).toList()

        assertFalse(
            "padded prompt must not enable case-bound evidence",
            hasProbeArtifacts(reliability),
        )
        assertTrue(sha256HexUtf8(request.input) != SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
    }

    @Test
    fun nonExactPromptDoesNotBindEvenIfSomeoneFlipsFlagManually() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            // Fail-closed: builder with caseBound=true still requires exact prompt bytes.
            buildRuntime(
                context = context,
                config = fixtureProviderConfig(),
                requestedProfile = fixtureProfile(),
                streamEnabled = false,
                useSimulatedModel = true,
                workspaceRoot = appWorkspaceRoot(context),
                sessionEventLogRoot = appSessionEventLogRoot(context, DEBUG_SESSION_ID),
                sessionContext = "",
                evidenceRecorder = null,
                diagnostics = noopDiagnostics(context),
                mcpProviders = emptyList(),
                userInput = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  ",
                caseBound = true,
            )
            org.junit.Assert.fail("must refuse non-exact prompt when caseBound=true")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun virtualWorkspaceRunStillWritesDurableEventLogUnderTheConfiguredRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "builder-durable-session"
        val root = appSessionEventLogRoot(context, sessionId)
        root.deleteRecursively()

        val text = "finish with done durable eventlog"
        val runtime = productionBuildRuntime(context, text, sessionId)
        runtime.runRequest(productionRequest(text, "durable-1", sessionId)).toList()

        val health = runtime.diagnostics.getSessionEventLogHealth(sessionId, "durable-1")
        assertTrue(runtime.diagnostics.querySessionEvents(SessionEventQuery(sessionId, requestId = "durable-1")).isNotEmpty())
        assertTrue("expected DURABLE backend, got ${health.backendDurability}", health.backendDurability == SessionEventLogDurability.DURABLE)
        assertTrue(
            "expected event files under the configured root",
            root.walkTopDown().any { it.name.endsWith(".jsonl") && it.path.contains("session-events") },
        )
        // Semantic VFS must not shadow the EventLog: business workspace keeps no /sessions stream.
        assertFalse(
            appWorkspaceRoot(context).walkTopDown().any { it.name == "events.jsonl" },
        )
    }

    @Test
    fun canonicalModelRequestKeepsRawInputAndLeavesHistoryToTheSdk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "builder-context-session"
        val runId = "builder-context-run"
        val root = appSessionEventLogRoot(context, sessionId)
        root.deleteRecursively()
        val sessionContext =
            "- user=还是有点热 | assistant=需要我为您打开空调设置页面吗？ | " +
                "runStatus=COMPLETED | completion=PARTIAL"
        val previous = PreviousExchange(sessionId, "music-offer", "有什么新歌", "要播放新歌吗？", "COMPLETED", "SUCCEEDED")
        val runtime = buildRuntime(
            context = context,
            config = fixtureProviderConfig(),
            requestedProfile = fixtureProfile(),
            streamEnabled = false,
            useSimulatedModel = true,
            workspaceRoot = appWorkspaceRoot(context),
            sessionEventLogRoot = root,
            sessionContext = sessionContext,
            evidenceRecorder = null,
            diagnostics = noopDiagnostics(context),
            mcpProviders = emptyList(),
            userInput = "可以",
            previousExchange = previous,
        )

        runtime.runRequest(productionRequest("可以", runId, sessionId)).toList()

        val payload = runtime.diagnostics.replaySessionEvents(sessionId, runId)
            .single { it.type == SessionEventType.MODEL_REQUEST }
            .payload as SessionEventPayload.ModelRequest
        // The system prompt holds only what is the same every run, so the provider's prompt cache keeps it.
        assertTrue(payload.systemPrompt.contains(AppHost.persona.identity))
        assertFalse(payload.systemPrompt.contains("需要我为您打开空调设置页面吗？"))
        assertFalse(payload.systemPrompt.contains(previous.assistantText))
        // The input comes first, exactly as sent; the session context and previous exchange follow it as one message.
        val messages = payload.messages as cc.noharry.agent.spi.SessionJson.Array
        val contents = messages.values.map {
            ((it as cc.noharry.agent.spi.SessionJson.Object).values["content"] as cc.noharry.agent.spi.SessionJson.Text).value
        }
        assertEquals(2, contents.size)
        assertEquals("可以", contents[0])
        assertTrue(contents[1].startsWith(RUN_CONTEXT_HEADER))
        assertFalse(contents[1].contains("需要我为您打开空调设置页面吗？"))
        assertFalse(contents[1].contains("completion=PARTIAL"))
        assertFalse(contents[1].contains(previous.assistantText))
        assertFalse(contents[1].contains(AppHost.persona.identity))
        runtime.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClearDataProductionPathTest {
    @Test
    fun productionBuilderThenCoordinatorClearThenProductionBuilderRecreates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = agentReliabilityRoot(context)
        val secrets = File(context.filesDir, "provider-secrets.json").also {
            it.writeText("""{"key":"keep-me"}""")
        }
        reliability.deleteRecursively()

        // 1) Production builder write
        runProductionBench(context, "clear-r1")
        assertTrue(hasProbeArtifacts(reliability))

        // 2) Production coordinator entry
        val coordinator = SessionCoordinator(TranscriptStore())
        val result = coordinator.clearAllRuntimeData(context)
        assertTrue(result.ok)
        assertFalse(reliability.exists())
        assertTrue(secrets.isFile)
        assertTrue(secrets.readText().contains("keep-me"))

        // 3) Production builder recreate
        runProductionBench(context, "clear-r2")
        val runDir = findProbeRunDir(reliability)
        assertTrue(File(runDir, "probes.jsonl").isFile)
        assertTrue(File(runDir, "run-meta.json").readText().contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
    }

    private suspend fun runProductionBench(context: Context, requestId: String) {
        val text = SAMPLE_DEBUG_CASE_PACK_PROMPT
        val runtime = productionBuildRuntime(context, text)
        runtime.runRequest(productionRequest(text, requestId)).toList()
    }
}
