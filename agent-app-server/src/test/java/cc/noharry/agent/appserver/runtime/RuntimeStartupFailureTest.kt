package cc.noharry.agent.appserver.runtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.TranscriptStore
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.EVENT_TEXT
import cc.noharry.agent.appserver.skills.TestHostRuns
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.appserver.skills.testHostSkills
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A run whose runtime cannot be built fails visibly and lets the next one run; a cancelled build is no failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeStartupFailureTest {
    @After
    fun bareHost() {
        AppHost.install(runs = RunAddOns.NONE, skills = { emptyList() })
    }

    @Before
    fun installHost() {
        AppHost.install(runs = TestHostRuns, skills = ::testHostSkills)
    }

    @Test fun constructionCancellationPropagatesWithoutFalseFailureEvidence() {
        val request = RuntimeTurnRequest(requestId = "cancelled-build", sessionId = "cancel-session", input = "hello")
        val recorders = multiSessionRecorders(request)
        var cancelled = false
        var failed = false
        assertThrows(CancellationException::class.java) {
            runBlocking { constructRuntime(
                RuntimeRunScope(request.requestId, request.sessionId), recorders,
                SessionCoordinator(TranscriptStore()), null,
                onFailure = { failed = true }, onCancelled = { cancelled = true },
            ) { throw CancellationException("cancel build") } }
        }
        assertTrue(cancelled)
        assertFalse(failed)
        assertNull(recorders.runEvidenceRecorder.snapshot().finalStatus)
    }

    @Test fun realConstructionFailureDoesNotCrashOrConsumeAdmissionAndNextRunCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("app_server_runtime_skills", "app_server_basic_tools").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        loadRuntimeSkills(context).forEach { saveRuntimeSkill(context, it.id, true) }
        saveBasicTool(context, "read_file", false)
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val transcript = TranscriptStore()
        val coordinator = SessionCoordinator(transcript)
        val store = coordinator.ensure(context)
        // Earlier turns fill the session context, so the preloaded skills no longer fit and must be read from the
        // workspace, which read_file being off forbids: construction fails for real.
        repeat(8) { index ->
            store.appendTurn(
                SessionTurn(
                    "turn-$index", "request-$index", "run-$index", coordinator.activeSessionId, "debug",
                    "第 $index 次问路线和天气，" + "顺便聊聊周末去哪里玩".repeat(16), index * 1_000L, index * 1_000L + 500L,
                    "COMPLETED", "已经查好了：" + "明天多云转晴，适合出门走走".repeat(28), emptyList(),
                    completionOutcome = "SUCCEEDED",
                ),
            )
        }
        val statuses = mutableListOf<String>()
        val runner = RuntimeSessionRunner(
            scope, transcript, coordinator, { null }, ProviderConfig::load,
            {}, { statuses += it }, { false }, { true }, { emptyList() },
            { "non-stream" }, { "fixture" }, { ConfirmationMode.ASK_EVERY_TIME }, {},
        )
        AppHost.install(runs = object : RunAddOns by TestHostRuns {
            override fun systemRules(facts: cc.noharry.agent.appserver.runtime.RunFacts) = listOf("oversized ".repeat(3000))
        })
        try {
            repeat(2) {
                runner.start(context, "$MIXED_BROADCAST_TEXT，$EVENT_TEXT").join()
                assertFalse(runner.isRunning)
                assertEquals(0, runner.activeRunCount)
                assertNull(runner.runStartedAtEpochMillis)
                val failed = runner.activeRunSnapshots().single()
                assertEquals("FAILED", failed.lifecycleStatus)
                assertEquals("UNKNOWN", failed.logHealth)
                assertEquals("UNKNOWN", failed.completionOutcome)
                assertEquals("FAILED", store.readTurns(coordinator.activeSessionId).last().finalStatus)
                assertTrue(store.readTurns(coordinator.activeSessionId).last().finalResponse.contains("16 KiB"))
            }
            AppHost.install(runs = TestHostRuns, skills = ::testHostSkills)
            saveBasicTool(context, "read_file", true)
            runner.start(context, "介绍你自己").join()
            withTimeout(30000) {
                while (runner.activeRunCount != 0) delay(10)
            }
            val turn = store.readTurns(coordinator.activeSessionId).last()
            assertEquals("COMPLETED", turn.finalStatus)
            assertTrue(turn.finalResponse.contains(AppHost.persona.name))
            assertTrue(parent.isActive)
        } finally {
            parent.cancel()
        }
    }
}
