@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.*
import cc.noharry.agent.appserver.console.*
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProductionHistoryIsolationTest {
    @Test fun concurrentStorageRootsStayIsolatedAndHistorySurvivesFreshProcessCaches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstModel = HistoryModel()
        val secondModel = HistoryModel()
        val resumedModel = HistoryModel().also { it.release.complete(Unit) }
        val models = ArrayDeque(listOf(firstModel, secondModel, resumedModel))
        AppHost.install(simulatedModel = { models.removeFirst() })
        val ids = listOf("history-a", "history-b")
        ids.forEach { appSessionEventLogRoot(context, it).deleteRecursively() }
        RuntimeHistories.clearCache()
        try {
            val first = build(context, ids[0])
            val firstRun = async(Dispatchers.Default) { first.resumeThread(ids[0]).run("only-in-a") }
            withTimeout(5_000) { firstModel.entered.await() }
            val second = build(context, ids[1])
            val secondRun = async(Dispatchers.Default) { second.resumeThread(ids[1]).run("only-in-b") }
            withTimeout(5_000) { secondModel.entered.await() }
            firstModel.release.complete(Unit)
            secondModel.release.complete(Unit)
            assertEquals(TurnStatus.COMPLETED, firstRun.await().status)
            assertEquals(TurnStatus.COMPLETED, secondRun.await().status)
            first.close(); second.close()
            RuntimeHistories.clearCache()
            val resumed = build(context, ids[0])
            resumed.resumeThread(ids[0]).run("follow-up")
            val history = resumedModel.requests.single().messages.joinToString("\n") { it.content }
            assertTrue(history.contains("only-in-a"))
            assertFalse(history.contains("only-in-b"))
            assertTrue(resumed.resumeThread(ids[0]).items().filterIsInstance<ThreadItem.UserMessage>().size >= 2)
            resumed.close()
        } finally {
            firstModel.release.complete(Unit); secondModel.release.complete(Unit)
            RuntimeHistories.clearCache()
            AppHost.install(simulatedModel = null)
        }
    }

    private fun build(context: Context, id: String): AgentRuntime = buildRuntime(context, fixtureProviderConfig(), fixtureProfile(),
        false, true, appWorkspaceRoot(context), appSessionEventLogRoot(context, id), "", null, noopDiagnostics(context),
        emptyList(), sessionId = id)
}

private class HistoryModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val requests = mutableListOf<ModelRequest>()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request
        entered.complete(Unit)
        release.await()
        emit(ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""))
        emit(ModelEvent.Completed)
    }
}
