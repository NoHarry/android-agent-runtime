@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.console
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ContextPanelState
import cc.noharry.agent.appserver.protocol.LessonEntry
import cc.noharry.agent.appserver.protocol.TodoEntry
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.encodeSessionId
import cc.noharry.agent.appserver.runtime.loadContextPanel
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionFixture
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionHarness
import cc.noharry.agent.appserver.runtime.awaitUntil
import cc.noharry.agent.appserver.runtime.multiSessionRecorders
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Isolation guarantees for the event-sourced todo board: no on-disk todo file may reach the
 * panel, and stale async work may not overwrite the currently selected session.
 */
@RunWith(RobolectricTestRunner::class)
class TodoBoardIsolationTest {
    @Test
    fun legacyTodoFileOnDiskIsNeverLoadedIntoThePanel() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "session-with-legacy-file"
        writeLegacyTodoFile(context, sessionId)

        val panel = loadContextPanel(context)

        // The loader supplies lessons/skills only; a todo file left by an earlier build is
        // dead data and must not populate the card.
        assertEquals(emptyList<TodoEntry>(), panel.todo)
    }

    @Test
    fun outOfOrderPanelReloadsDoNotOverwriteTheSelectedSession() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val slowSessionPanel = panelWithLesson("session-a lesson")
            val fastSessionPanel = panelWithLesson("session-b lesson")
            val releaseSlowLoad = CompletableDeferred<Unit>()

            harness.coordinator.adoptActiveSessionId("session-a")
            coroutineScope {
                // Reload for A starts first but finishes last.
                val slowReload = async {
                    harness.runner.publishReloadedPanel("session-a") {
                        releaseSlowLoad.await()
                        slowSessionPanel
                    }
                }
                harness.coordinator.adoptActiveSessionId("session-b")
                harness.runner.publishReloadedPanel("session-b") { fastSessionPanel }
                assertEquals("session-b lesson", harness.runner.contextPanelState.lessons.single().content)

                releaseSlowLoad.complete(Unit)
                slowReload.await()
            }

            // A's late result is dropped because A is no longer selected.
            assertEquals("session-b lesson", harness.runner.contextPanelState.lessons.single().content)
        }
    }

    @Test
    fun finalizingRunDoesNotOverwriteTheSessionSwitchedToDuringFinalization() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RuntimeMultiSessionHarness().use { harness ->
            lateinit var harnessRef: RuntimeMultiSessionHarness
            harnessRef = harness
            // Switch sessions from inside the finalization window: ownsUi was already captured
            // as true, so only a re-check at the panel write can keep session-other clean.
            val planned = RuntimeMultiSessionFixture(
                sessionId = "session-finalizing",
                runId = "run-finalizing",
                emitTodo = true,
                onFinalizing = {
                    // Same synchronous switch the UI performs: select, then project.
                    harnessRef.coordinator.adoptActiveSessionId("session-other")
                    harnessRef.runner.projectSelectedSessionState()
                },
            )

            harness.coordinator.adoptActiveSessionId(planned.sessionId)
            harness.runner.startPreparedRun(
                sessionId = planned.sessionId,
                request = planned.request,
                runtime = planned.runtime,
                resources = planned.resources(),
                recorders = multiSessionRecorders(planned.request),
                context = context,
                text = planned.request.input,
            )
            assertTrue(planned.entered.await(2, TimeUnit.SECONDS))
            assertTrue(awaitUntil { harness.runner.contextPanelState.todo.isNotEmpty() })

            planned.release.complete(Unit)
            assertTrue(planned.finished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitUntil { harness.awaitFinishedSnapshot(planned.sessionId) })

            assertEquals("session-other", harness.coordinator.activeSessionId)
            assertTrue(
                "finalization leaked ${harness.runner.contextPanelState.todo} into session-other",
                harness.runner.contextPanelState.todo.isEmpty(),
            )
        }
    }

    @Test
    fun creatingNewSessionClearsBoardSynchronouslyThroughReset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val releaseReload = CountDownLatch(1)
        RuntimeMultiSessionHarness().use { harness ->
            val planned = RuntimeMultiSessionFixture("session-a", "run-a", emitTodo = true)
            harness.startHeld(planned)
            assertTrue(awaitUntil { harness.runner.contextPanelState.todo.isNotEmpty() })

            harness.coordinator.adoptActiveSessionId("session-b")
            try {
                harness.runner.resetForSession(BlockingApplicationContext(context, releaseReload))
                // The async lessons/skills reload cannot complete until the latch opens, so this
                // assertion is satisfied only by resetForSession's synchronous projection.
                assertTrue(harness.runner.contextPanelState.todo.isEmpty())
            } finally {
                releaseReload.countDown()
            }

            planned.release.complete(Unit)
            assertTrue(planned.finished.await(2, TimeUnit.SECONDS))
        }
    }
}

private fun panelWithLesson(content: String): ContextPanelState =
    ContextPanelState(
        visible = true,
        todo = emptyList(),
        lessons = listOf(LessonEntry(scene = "test", content = content, confidence = 1f, hitCount = 1)),
        skills = emptyList(),
    )

private fun writeLegacyTodoFile(context: Context, sessionId: String) {
    val todoDir = File(File(appWorkspaceRoot(context), "memory"), "todo").apply { mkdirs() }
    File(todoDir, "${encodeSessionId(sessionId)}.json").writeText(
        """{"sessionId":"$sessionId","items":[{"content":"旧构建写下的任务","status":"completed"}],"updatedAtEpochMillis":1}""",
    )
}

private class BlockingApplicationContext(
    base: Context,
    private val release: CountDownLatch,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context {
        check(release.await(2, TimeUnit.SECONDS)) { "Timed out waiting to release context-panel reload" }
        return baseContext.applicationContext
    }
}
