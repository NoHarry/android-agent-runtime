@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.TranscriptStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRequestIdentityTest {
    @Test fun runnerPassesTheFullRequestThroughAdmissionWithoutDroppingModelOrScene() = runBlocking {
        val fixture = RuntimeMultiSessionFixture("s", "r")
        val request = fixture.request.copy(modelRef = ModelRef("openai", "deepseek-flash", "thinking"), scene = "test-scene")
        var observed: cc.noharry.agent.ThreadOptions? = null
        val runtime = object : AgentRuntime by fixture.runtime {
            override fun resumeThread(threadId: String, options: cc.noharry.agent.ThreadOptions) = fixture.runtime.resumeThread(threadId, options).also { observed = options }
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val transcript = TranscriptStore()
        val coordinator = SessionCoordinator(transcript)
        val runner = multiSessionRunner(scope, transcript, coordinator, mutableListOf())
        try {
            coordinator.adoptActiveSessionId("s")
            runner.startPreparedRun("s", request, runtime, fixture.resources(), multiSessionRecorders(request))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            org.junit.Assert.assertEquals(request.modelRef, observed?.model)
            org.junit.Assert.assertEquals(request.scene, observed?.scene)
        } finally {
            fixture.release.complete(Unit)
            job.cancelAndJoin()
        }
    }
}
