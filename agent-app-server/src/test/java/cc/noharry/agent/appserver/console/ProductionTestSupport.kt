@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.console

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
import cc.noharry.agent.appserver.runtime.beforeThinkingLine
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

/** Same decision + builder shape as [RuntimeSessionRunner] production intake. */
internal fun productionBuildRuntime(
    context: Context,
    userInput: String,
    sessionId: String = DEBUG_SESSION_ID,
): AgentRuntime {
    val caseBound = productionCaseBoundDecision(userInput)
    return buildRuntime(
        context = context,
        config = fixtureProviderConfig(),
        requestedProfile = fixtureProfile(),
        streamEnabled = false,
        useSimulatedModel = true,
        workspaceRoot = appWorkspaceRoot(context),
        sessionEventLogRoot = appSessionEventLogRoot(context, sessionId),
        sessionContext = "",
        evidenceRecorder = null,
        diagnostics = noopDiagnostics(context),
        mcpProviders = emptyList(),
        userInput = userInput,
        caseBound = caseBound,
    )
}

internal fun productionRequest(
    userInput: String,
    requestId: String,
    sessionId: String = DEBUG_SESSION_ID,
): RuntimeTurnRequest =
    agentRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = userInput,
        config = fixtureProviderConfig(),
        profile = fixtureProfile(),
    )

internal fun fixtureProfile(): ProviderProfile =
    ProviderProfile(
        id = "fixture-sim",
        label = "fixture",
        provider = ModelProvider.OPENAI,
        apiKey = "sk-test",
        model = "fixture",
        baseUrl = "https://example.test/v1",
    )

internal fun fixtureProviderConfig(): ProviderConfig {
    val profile = fixtureProfile()
    return ProviderConfig(
        values = emptyMap(),
        sourcePath = "test",
        expectedPaths = emptyList(),
        profiles = listOf(profile),
        defaultProfileId = profile.id,
        activeProfileId = profile.id,
        manualProfileEnabled = false,
    )
}

internal fun noopDiagnostics(context: Context): AppDiagnostics =
    appDiagnostics(
        context = context,
        requestId = "test-diag",
        onEntry = {},
        onTokenUsage = {},
    )

internal fun hasProbeArtifacts(reliabilityRoot: File): Boolean {
    val runs = File(reliabilityRoot, "runs")
    if (!runs.isDirectory) return false
    return runs.listFiles()?.any { File(it, "probes.jsonl").isFile } == true
}

internal fun findProbeRunDir(reliabilityRoot: File): File {
    val runs = File(reliabilityRoot, "runs")
    assertTrue("expected runs under $reliabilityRoot", runs.isDirectory)
    val withProbes = runs.listFiles()?.firstOrNull { File(it, "probes.jsonl").isFile }
    assertTrue("expected probes under $reliabilityRoot", withProbes != null)
    return withProbes!!
}

private fun assertTrue(message: String, condition: Boolean) {
    org.junit.Assert.assertTrue(message, condition)
}
