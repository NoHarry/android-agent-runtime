@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.runtime.runRequest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.view.searchIndexText
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.appserver.console.productionBuildRuntime
import cc.noharry.agent.appserver.console.productionRequest
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Privacy regression: API keys, Authorization, bearer tokens and cookies must never reach
 * EventLog files. Complete logical prompt and tool bodies must persist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrajectoryPrivacyTest {

    @Test
    fun eventLogPersistsPromptAndRejectsTransportCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "privacy-session"
        val root = appSessionEventLogRoot(context, sessionId).also { it.deleteRecursively() }

        val runtime = productionBuildRuntime(context, SECRET_PROMPT, sessionId)
        runtime.runRequest(productionRequest(SECRET_PROMPT, "privacy-run", sessionId)).toList()
        val events = runtime.diagnostics.replaySessionEvents(sessionId, "privacy-run")
        runtime.close()

        assertTrue(events.isNotEmpty())
        val onDisk = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(onDisk.contains(SECRET_PROMPT))
        assertFalse(onDisk.contains(SECRET_TOKEN))
        assertFalse(onDisk.contains("Authorization"))
        assertFalse(onDisk.contains("Bearer "))
    }

    @Test
    fun projectedRecordsKeepPromptAuthorityWithoutCredentialLeak() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "privacy-projection-session"
        appSessionEventLogRoot(context, sessionId).deleteRecursively()

        val runtime = productionBuildRuntime(context, SECRET_PROMPT, sessionId)
        runtime.runRequest(productionRequest(SECRET_PROMPT, "privacy-run", sessionId)).toList()
        val events = runtime.diagnostics.replaySessionEvents(sessionId, "privacy-run")
        val records = events.map { it.toTrajectoryRecord() }
        runtime.close()

        assertTrue(events.any { event -> event.toString().contains(SECRET_PROMPT) })
        val rendered = records.joinToString("\n") { record ->
            listOf(
                record.title,
                record.summary,
                record.safeMetadata.entries.joinToString { "${it.key}=${it.value}" },
                record.searchIndexText(),
            ).joinToString(" ")
        }
        assertFalse(rendered.contains(SECRET_TOKEN))
    }

    @Test
    fun loadFailureBoundaryCarriesNoPathOrExceptionText() {
        val failure = TrajectoryEventLogFailure(
            TrajectoryEventLogReason.READER_BUILD_FAILED,
            IllegalStateException("/data/user/0/com.example.host/no_backup/secret"),
        )

        val record = loadErrorRecord("s1", "run-1", failure.reasonCode)

        assertFalse(record.summary.contains("/data/"))
        assertFalse(record.title.contains("/data/"))
        assertTrue(record.safeMetadata.values.none { it.contains(File.separator) })
        // The cause is still retained on the exception so logcat can name the real problem.
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun projectedToolRowsDoNotIndexTransportCredentials() {
        val record = sessionEvent(
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "call-a", "toolName" to "http_post"),
        ).toTrajectoryRecord()

        assertFalse(record.searchIndexText().contains(SECRET_TOKEN.lowercase()))
        assertFalse(record.searchIndexText().contains("authorization"))
    }

    @Test
    fun uiNeverRendersTheEventLogRootPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val health = composeTrajectoryHealth(
            runtimeHealth = null,
            projection = null,
            richAvailability = TrajectoryRichAvailability.UNAVAILABLE,
            safeReasonCode = TrajectoryEventLogReason.READER_BUILD_FAILED,
        )
        val rootPath = sessionEventLogBaseRoot(context).absolutePath

        assertFalse(health.safeReasonCode.orEmpty().contains(File.separator))
        assertFalse(health.badgeLabel.contains(rootPath))
    }
}

private const val SECRET_PROMPT = "finish with done and never leak swordfish-passphrase"
private const val SECRET_TOKEN = "sk-live-swordfish-passphrase"
