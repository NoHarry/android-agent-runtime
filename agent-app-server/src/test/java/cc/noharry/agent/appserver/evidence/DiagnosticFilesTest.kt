package cc.noharry.agent.appserver.evidence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.productionRequest
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.createRunRecorders
import cc.noharry.agent.appserver.server.AppServerConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A host keeps the server's diagnostic files only when it asks ([AppServerConfig.diagnostics]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dirs = listOf("runtime-debug-logs", "debug-run-evidence", "mcp-progressive-evidence").map { File(context.filesDir, it) }

    @Before
    fun clean() = dirs.forEach { it.deleteRecursively() }

    @After
    fun bare() = AppHost.install(diagnostics = false)

    @Test
    fun aBareConfigTurnsNothingOn() {
        val config = AppServerConfig(name = "bare")
        assertFalse(config.diagnostics)
        assertFalse(config.debugSockets)
        assertTrue(config.plugins.isEmpty())
        with(config.builtIns) {
            assertFalse(fileWriting || web || scripts || subagents)
        }
        assertEquals(emptySet<String>(), BuiltInFeatures.NONE.offered())
    }

    @Test
    fun withoutDiagnosticsARunWritesNoneOfThem() = runBlocking {
        AppHost.install(diagnostics = false)
        val recorders = run("quiet-1")
        assertNull("no tool-search evidence for the simulated backend", recorders.evidenceRecorder)
        assertNull(recorders.runEvidenceRecorder.write())
        dirs.forEach { assertFalse("${it.name} was written", it.exists()) }
    }

    @Test
    fun withDiagnosticsEachRunKeepsItsFiles() = runBlocking {
        AppHost.install(diagnostics = true)
        val recorders = run("kept-1")
        assertNotNull(recorders.evidenceRecorder?.write())
        assertTrue(recorders.runEvidenceRecorder.write()!!.isFile)
        dirs.forEach { assertTrue("${it.name} missing", it.isDirectory && it.list().orEmpty().isNotEmpty()) }
    }

    /** What a simulated run's start records: the model call's log, then the run's recorders. */
    private suspend fun run(requestId: String): cc.noharry.agent.appserver.runtime.RuntimeRunRecorders {
        val diagnostics = appDiagnostics(context, requestId, onEntry = {}, onTokenUsage = {})
        diagnostics.recordSessionStart(ModelProvider.OPENAI, "non-stream", "test")
        diagnostics.flush()
        val request = productionRequest("hello", requestId)
        return createRunRecorders(context, request, "hello", fixtureProfile(), simulated = true, streamLabel = "non-stream", backendLabel = "simulated")
    }
}
