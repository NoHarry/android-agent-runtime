@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.console

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnInput
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.appserver.runtime.ExecutionEvents
import cc.noharry.agent.appserver.runtime.ExecutionMessage
import cc.noharry.agent.appserver.runtime.ExecutionToolHook
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.buildRuntime
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.SessionEventQuery
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A run built as production builds one, with the status hook another agent's task follows ([ExecutionToolHook]) and the
 * fixture model, without network or device tools: each tool call's typed status goes out under the turn, the run
 * completes, and its canonical log is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProductionStatusHookTest {
    @Test fun productionBuilderInstallsTheStatusHookAndKeepsTheCanonicalLog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "a2a-${UUID.randomUUID()}"
        val request = productionRequest("你是谁", "a2a-test", sessionId)
        val subscription = ExecutionEvents.subscribe(request.requestId)
        val runtime = buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), streamEnabled = true, useSimulatedModel = true,
            workspaceRoot = appWorkspaceRoot(context), sessionEventLogRoot = appSessionEventLogRoot(context, sessionId),
            sessionContext = "", evidenceRecorder = null, diagnostics = noopDiagnostics(context), mcpProviders = emptyList(),
            userInput = request.input,
            hostHook = ExecutionToolHook(),
        )
        try {
            val events = request.thread(runtime).runStreamed(TurnInput.text(request.input), request.options()).toList()
            val messages = buildList { while (true) add(subscription.events.tryReceive().getOrNull() ?: break) }
            val finish = messages.filterIsInstance<ExecutionMessage.ToolStatus>().filter { it.name == "finish" }
            assertEquals(listOf("started", "completed"), finish.map { it.phase })
            assertEquals(TurnStatus.COMPLETED, events.filterIsInstance<ThreadEvent.TurnCompleted>().single().result.status)
            assertTrue(runtime.diagnostics.querySessionEvents(SessionEventQuery(sessionId, requestId = request.requestId)).isNotEmpty())
        } finally {
            subscription.close()
            runtime.close()
        }
    }
}
