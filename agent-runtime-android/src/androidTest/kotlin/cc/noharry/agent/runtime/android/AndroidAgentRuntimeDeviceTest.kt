package cc.noharry.agent.runtime.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnOptions
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
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
        File(context.noBackupFilesDir, "agent-runtime").deleteRecursively()
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest): Flow<ModelEvent> =
                flowOf(ModelEvent.TextDelta("hello from the device"), ModelEvent.Completed)
        }
        val runtime = AndroidAgentRuntime.builder(context)
            .model(model)
            .tools(functionTools("device") { tool("noop", "Does nothing.") { "ok" } })
            .build()

        val events = runtime.startThread(ThreadOptions(threadId = "device-session")).runStreamed("hi", TurnOptions(turnId = "device-run")).toList()

        assertTrue(events.toString(), events.any { it is ThreadEvent.TurnCompleted })
        assertTrue(events.none { it is ThreadEvent.TurnFailed })
        assertTrue(File(root, "workspace/runtime-session-store.sqlite").isFile)
        runtime.close()
        AndroidAgentRuntime.builder(context).model(model).build().use { restored ->
            assertTrue(restored.resumeThread("device-session").items().isNotEmpty())
        }
        root.deleteRecursively()
        File(context.noBackupFilesDir, "agent-runtime").deleteRecursively()
        Unit
    }
}
