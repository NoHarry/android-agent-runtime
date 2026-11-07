@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.android

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnStatus
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Unpinned, Robolectric runs this library's tests on Android 4.4, which has no no-backup directory.
@Config(sdk = [33])
class AndroidAgentRuntimeTest {
    private val model = object : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        override fun complete(request: ModelRequest) = flowOf(ModelEvent.Completed)
    }

    @Test
    fun deletingAThreadThroughTheAndroidFacadeRemovesItsSqliteRows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val responding = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall("finish", "finish", """{"response":"done"}"""), ModelEvent.Completed,
            )
        }
        AndroidAgentRuntime.builder(context, "deletion").model(responding).build().use { runtime ->
            assertEquals(TurnStatus.COMPLETED, runtime.startThread(ThreadOptions(threadId = "removed")).run("hello").status)
            val dbFile = File(context.filesDir, "agent-runtime/runtimes/deletion/workspace").walkTopDown()
                .single { it.name == "runtime-session-store.sqlite" }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM messages", null).use { it.moveToFirst(); assertTrue(it.getInt(0) > 0) }
            }
            runtime.deleteThread("removed")
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                listOf("sessions", "runs", "messages", "tool_events", "usage", "vfs_ops", "model_calls").forEach { table ->
                    db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
                }
            }
        }
    }

    @Test
    fun aNamedRuntimeKeepsItsFilesApart() {
        val context = RuntimeEnvironment.getApplication()

        AndroidAgentRuntime.builder(context, "second").model(model).build().close()

        assertTrue(File(context.filesDir, "agent-runtime/runtimes/second/workspace").isDirectory)
        assertFalse(File(context.filesDir, "agent-runtime/workspace").exists())
        assertThrows(IllegalArgumentException::class.java) { AndroidAgentRuntime.builder(context, "../up") }
        assertThrows(IllegalArgumentException::class.java) { AndroidAgentRuntime.builder(context, "") }
    }
}
