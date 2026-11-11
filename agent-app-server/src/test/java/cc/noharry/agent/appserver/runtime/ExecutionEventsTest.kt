package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.*
import cc.noharry.agent.appserver.evidence.DiagnosticWrites
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionEventsTest {
    @Test fun theToolHookPublishesTypedStatusUnderTheTurnButNeverThePayload() = runBlocking {
        val subscription = ExecutionEvents.subscribe("hooked-turn")
        val hook = ExecutionToolHook()
        val call = cc.noharry.agent.spi.ToolUse("navigate", """{"token":"secret"}""", cc.noharry.agent.spi.ToolSource.BACKEND, "c1")
        // Published under the turn's id (requestId), which is what a host calls its run.
        val context = cc.noharry.agent.spi.HookContext(sessionId = "s", runId = "execution", requestId = "hooked-turn")
        hook.onPreToolUse(call, context)
        hook.onPostToolUse(call, cc.noharry.agent.spi.ToolUseResult(ok = false, resultJson = "private-result"), context)
        val messages = List(2) { withTimeout(5_000) { subscription.events.receive() } }
        subscription.close()
        assertEquals(listOf(ExecutionMessage.ToolStatus("navigate", "c1", "started"), ExecutionMessage.ToolStatus("navigate", "c1", "failed")), messages)
        assertFalse(messages.toString().contains("secret"))
        assertFalse(messages.toString().contains("private-result"))
    }

    @Test fun twoSubscribersReceiveTheSameOrderedMessagesWithoutReexecutingProducer() = runBlocking {
        val id = UUID.randomUUID().toString()
        val ui = ExecutionEvents.subscribe(id)
        val a2a = ExecutionEvents.subscribe(id)
        val messages = listOf(
            ExecutionMessage.Runtime(testTurnStarted(id)),
            ExecutionMessage.ToolStatus("read_file", "c1", "started"),
            ExecutionMessage.Speech("正在查询。", false),
            ExecutionMessage.Ended,
        )
        var executions = 0
        withContext(Dispatchers.Default) { executions++; messages.forEach { ExecutionEvents.publish(id, it) } }
        ExecutionEvents.closeRun(id)
        assertEquals(messages, buildList { for (event in ui.events) add(event) })
        assertEquals(messages, buildList { for (event in a2a.events) add(event) })
        assertEquals(1, executions)
        ui.close(); a2a.close()
    }

    @Test fun slowOptionalSubscriberFailsExplicitlyWithoutBlockingOtherSubscriber() = runBlocking {
        val id = UUID.randomUUID().toString()
        val slow = ExecutionEvents.subscribe(id, capacity = 1)
        val fast = ExecutionEvents.subscribe(id)
        val message = ExecutionMessage.Speech("text", false)
        repeat(3) { ExecutionEvents.publish(id, message) }
        repeat(3) { assertEquals(message, fast.events.receive()) }
        assertEquals(message, slow.events.receive())
        assertTrue(slow.events.receiveCatching().exceptionOrNull() is IllegalStateException)
        slow.close(); fast.close()
    }

    @Test fun requiredOverflowIsReportedToPublisherRatherThanSilentlyLosingProjection() {
        val id = UUID.randomUUID().toString()
        val logs = ExecutionEvents.subscribe(id, required = true, capacity = 1)
        try {
            ExecutionEvents.publish(id, ExecutionMessage.Stopped)
            assertThrows(IllegalStateException::class.java) { ExecutionEvents.publish(id, ExecutionMessage.Ended) }
        } finally { logs.close() }
    }

    @Test fun subscriptionRemovalAndRunIdsAreIsolated() = runBlocking {
        val first = ExecutionEvents.subscribe("topic-a")
        val second = ExecutionEvents.subscribe("topic-b")
        first.close()
        ExecutionEvents.publish("topic-a", ExecutionMessage.Stopped)
        assertTrue(second.events.tryReceive().isFailure)
        ExecutionEvents.publish("topic-b", ExecutionMessage.Ended)
        assertEquals(ExecutionMessage.Ended, second.events.receive())
        second.close()
    }

    @Test fun diagnosticsFlushAcknowledgesOrderedAsyncWritesAndReportsFailure() = runBlocking {
        val dir = Files.createTempDirectory("execution-diagnostics").toFile()
        val file = dir.resolve("${UUID.randomUUID()}.log")
        DiagnosticWrites.publish(file, "first\n")
        DiagnosticWrites.publish(file, "second\n")
        DiagnosticWrites.flush(file.name)
        assertEquals("first\nsecond\n", file.readText())
        val invalid = dir.resolve(UUID.randomUUID().toString()).apply { mkdir() }
        DiagnosticWrites.publish(invalid, "must fail")
        assertTrue(runCatching { DiagnosticWrites.flush(invalid.name) }.isFailure)
    }


    @Test fun staleTransportCancellationCannotCancelReplacementInSharedService() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val first = RuntimeMultiSessionFixture("shared-external", "old-task")
            val replacement = RuntimeMultiSessionFixture("shared-external", "new-task")
            harness.startHeld(first)
            harness.startHeld(replacement)
            harness.runner.cancelRun(first.sessionId, first.runId).join()
            assertTrue(harness.runner.activeRunSnapshots().any { it.runId == replacement.runId && it.isActive })
            replacement.release.complete(Unit)
            assertTrue(awaitUntil { harness.runner.activeRunSnapshots().none { it.isActive } })
        }
    }

    @Test fun finishingRequiredSubscriberDetachesBeforeDrainingAcceptedMessages() = runBlocking {
        val id = UUID.randomUUID().toString()
        val logs = ExecutionEvents.subscribe(id, required = true)
        val observer = ExecutionEvents.subscribe(id)
        ExecutionEvents.publish(id, ExecutionMessage.Stopped)
        logs.finish()
        ExecutionEvents.publish(id, ExecutionMessage.ObserverIssue("ui"))
        assertEquals(ExecutionMessage.Stopped, logs.events.receive())
        assertTrue(logs.events.receiveCatching().isClosed)
        assertEquals(ExecutionMessage.Stopped, observer.events.receive())
        assertEquals(ExecutionMessage.ObserverIssue("ui"), observer.events.receive())
        logs.close(); observer.close()
    }
}
