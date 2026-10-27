@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class RuntimeSteeringQueueTest {
    @Test fun acceptedInputCannotLoseAgainstTerminalSealing() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val queue = queue()
                val gate = CyclicBarrier(2)
                val post = workers.submit<PostUserInputResult> { gate.await(); queue.post(input()) }
                val seal = workers.submit<Boolean> { gate.await(); queue.sealIfIdle("run") }
                if (post.get() == PostUserInputResult.Accepted) {
                    assertFalse(seal.get())
                    assertEquals(listOf("answer"), queue.drain("run").map { it.text })
                } else {
                    assertTrue(seal.get())
                    assertTrue(queue.drain("run").isEmpty())
                }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun cancellationRecordsQueuedAndDrainedButUnconsumedAnswers() {
        val facts = mutableListOf<UserInteraction>()
        val queue = queue(facts)
        assertEquals(PostUserInputResult.Accepted, queue.post(input()))
        queue.drain("run")
        queue.post(input().copy(requestId = "second"))
        queue.close("run", "CANCELLED")
        assertEquals(2, facts.count { it.state == "UNCONSUMED" && it.reason == "CANCELLED" })
        assertEquals(PostUserInputResult.RunNotActive, queue.post(input().copy(requestId = "late")))
    }

    @Test fun deduplicationAndLimitsDoNotSilentlyDropAcceptedInput() {
        val queue = queue()
        assertEquals(PostUserInputResult.Accepted, queue.post(input()))
        assertEquals(PostUserInputResult.Duplicate, queue.post(input()))
        assertEquals(PostUserInputResult.Rejected("REQUEST_ID_CONFLICT"), queue.post(input().copy(text = "changed")))
        assertEquals(PostUserInputResult.Rejected("SESSION_MISMATCH"), queue.post(input().copy(sessionId = "other")))
        repeat(63) { assertEquals(PostUserInputResult.Accepted, queue.post(input().copy(requestId = "extra-$it"))) }
        assertEquals(PostUserInputResult.Rejected("INPUT_LIMIT"), queue.post(input().copy(requestId = "overflow")))
        assertEquals(64, queue.drain("run").size)
    }

    private fun input() = PostUserInputRequest("run", "input", "answer", "session")

    @Test fun questionAnswerIsCorrelatedAndUnansweredQuestionsDoNotPreventSealing() {
        val facts = mutableListOf<UserInteraction>()
        val queue = queue(facts)
        val ids = queue.questions("run", "ask", listOf(
            UserInteraction("QUESTION", "PENDING", text = "Destination?"),
            UserInteraction("QUESTION", "PENDING", text = "Time?"),
        )).map { it.questionId }
        assertEquals(PostUserInputResult.Accepted, queue.post(input().copy(questionId = ids.first())))
        assertFalse(queue.sealIfIdle("run"))
        val answer = queue.drain("run").single()
        assertEquals("Destination?", answer.questionText)
        queue.consumed(answer)
        assertEquals(PostUserInputResult.Rejected("QUESTION_ALREADY_ANSWERED"), queue.post(input().copy(requestId = "other", questionId = ids.first())))
        assertTrue(queue.sealIfIdle("run"))
        queue.close("run", "COMPLETED")
        assertTrue(facts.any { it.questionId == ids.last() && it.state == "EXPIRED" })
    }

    @Test fun inputAdmittedBeforeTheLoopOpensIsBufferedThenRecordedInOrderAndProjectedOnce() {
        val facts = mutableListOf<UserInteraction>()
        val queue = RuntimeSteeringQueue().apply { admit("run", "session") }
        assertEquals(PostUserInputResult.Accepted, queue.post(input()))
        assertEquals(PostUserInputResult.Rejected("SESSION_MISMATCH"), queue.post(input().copy(requestId = "x", sessionId = "other")))
        assertTrue(facts.isEmpty())
        queue.open(MaterializerContext("session", "run", "run"), recorder(facts))
        assertEquals(listOf("RECEIVED"), facts.map { it.state })
        assertEquals(listOf("RECEIVED"), queue.takePublicFacts("run").map { it.state })
        assertTrue(queue.takePublicFacts("run").isEmpty())
        assertEquals(listOf("answer"), queue.drain("run").map { it.text })
    }

    @Test fun closeRecordsTheReasonOnceAndLaterConsumptionOrClearAddsNothing() {
        val facts = mutableListOf<UserInteraction>()
        val queue = queue(facts)
        val question = queue.questions("run", "ask", listOf(UserInteraction("QUESTION", "PENDING", text = "Destination?"))).single()
        assertEquals(PostUserInputResult.Accepted, queue.post(input().copy(questionId = question.questionId)))
        val drained = queue.drain("run").single()
        queue.close("run", "CANCELLED")
        val closing = facts.filter { it.state == "UNCONSUMED" || it.state == "EXPIRED" }
        assertEquals(listOf("CANCELLED", "CANCELLED"), closing.map { it.reason })
        assertEquals(listOf("PENDING", "RECEIVED", "ANSWER_RECEIVED", "UNCONSUMED", "EXPIRED"), queue.takePublicFacts("run").map { it.state })
        val recorded = facts.size
        queue.consumed(drained)
        queue.close("run", "COMPLETED")
        assertTrue(queue.takePublicFacts("run").isEmpty())
        queue.clear("run")
        assertEquals(recorded, facts.size)
        assertEquals(PostUserInputResult.NotFound, queue.post(input().copy(requestId = "late")))
    }

    @Test fun oneRunsSlowRecorderDoesNotBlockAnotherRunsInput() {
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : LoopSessionRecorder by LoopSessionRecorder.noop() {
            override fun recordInteraction(context: MaterializerContext, interaction: UserInteraction) { release.await() }
        }
        val queue = RuntimeSteeringQueue().apply {
            open(MaterializerContext("session", "slow", "slow"), blocking)
            open(MaterializerContext("session", "fast", "fast"), LoopSessionRecorder.noop())
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val slow = workers.submit<PostUserInputResult> { queue.post(input().copy(runId = "slow")) }
            Thread.sleep(100)
            val fast = workers.submit<PostUserInputResult> { queue.post(input().copy(runId = "fast")) }
            assertEquals(PostUserInputResult.Accepted, fast.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(slow.isDone)
            release.countDown()
            assertEquals(PostUserInputResult.Accepted, slow.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun questionsOnAClosedRunAreRefusedAsUnavailableNotAsBadArguments() {
        val queue = queue()
        queue.close("run", "COMPLETED")
        val error = runCatching { queue.questions("run", "ask", listOf(UserInteraction("QUESTION", "PENDING", text = "?"))) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    private fun recorder(facts: MutableList<UserInteraction>) = object : LoopSessionRecorder by LoopSessionRecorder.noop() {
        override fun recordInteraction(context: MaterializerContext, interaction: UserInteraction) { facts += interaction }
    }

    private fun queue(facts: MutableList<UserInteraction> = mutableListOf()): RuntimeSteeringQueue =
        RuntimeSteeringQueue().apply { open(MaterializerContext("session", "run", "run"), recorder(facts)) }
}
