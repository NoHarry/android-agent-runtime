package cc.noharry.agent.runtime.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.runtime.functionTools
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The one-line Android setup on a real device: a run answers, and its session lands in the SQLite store. */
@RunWith(AndroidJUnit4::class)
class AndroidAgentRuntimeDeviceTest {
    @Test
    fun aRunAnswersAndItsSessionIsKeptInSqlite(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "agent-runtime").apply { deleteRecursively() }
        val model = object : ModelClient {
            override fun complete(request: ModelRequest): Flow<ModelEvent> =
                flowOf(ModelEvent.TextDelta("hello from the device"), ModelEvent.Completed)
        }
        val runtime = AndroidAgentRuntime.builder(context)
            .modelClient(model)
            .toolProvider(functionTools("device") { tool("noop", "Does nothing.") { "ok" } })
            .build()

        val events = runtime.run(AgentRequest("device-run", "device-session", "hi")).toList()

        assertTrue(events.toString(), events.any { it is AgentEvent.Finished || it is AgentEvent.ResultAvailable })
        assertTrue(events.none { it is AgentEvent.Failed })
        assertTrue(File(root, "workspace/runtime-session-store.sqlite").isFile)
        root.deleteRecursively()
        Unit
    }
}
