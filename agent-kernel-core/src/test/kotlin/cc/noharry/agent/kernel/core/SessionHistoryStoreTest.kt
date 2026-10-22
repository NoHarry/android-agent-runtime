package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryStoreTest {
    @Test
    fun laterRunStartsWithEarlierRunsUnchangedAndOnlyAppends() {
        val store = SessionHistoryStore()
        store.start("run-1", "session")
        val first = listOf(user("空调调到22度"), call("c1", "set_temp"), result("c1"), assistant("已调到22度"))
        store.record("run-1", first)

        store.start("run-2", "session")
        val own = listOf(user("再高一点"))

        assertEquals(first + own, store.prefixed("run-2", own))
        assertEquals(own, store.prefixed("run-unknown", own))
        assertTrue(store.messages("other").isEmpty())
    }

    @Test
    fun onlyTheMostRecentlyUsedSessionsAreKept() {
        val store = SessionHistoryStore(maxSessions = 2)
        listOf("a", "b").forEach { session ->
            store.start("run-$session", session)
            store.record("run-$session", listOf(user(session)))
        }
        // Reading "a" makes it the most recent, so a third session pushes out "b".
        assertEquals(listOf(user("a")), store.messages("a"))
        store.start("run-c", "c")

        assertEquals(listOf(user("a")), store.messages("a"))
        assertTrue(store.messages("b").isEmpty())
    }

    @Test
    fun callsLeftWithoutAResultGetOneSoTheNextRequestIsWellFormed() {
        val store = SessionHistoryStore()
        store.start("run-1", "session")
        store.record("run-1", listOf(user("导航"), call("c1", "navigate")))

        val kept = store.messages("session")
        assertEquals(listOf("user", "assistant", "tool"), kept.map { it.role })
        assertEquals("c1", kept.last().toolCallId)
    }

    @Test
    fun aRunThatStartedBeforeANewerOneLeavesTheHistoryAlone() {
        val store = SessionHistoryStore()
        store.start("older", "session")
        store.start("newer", "session")
        store.record("older", listOf(user("旧的")))
        assertTrue(store.messages("session").isEmpty())

        store.record("newer", listOf(user("新的")))
        assertEquals(listOf("新的"), store.messages("session").map { it.content })
    }

    @Test
    fun pastTheCapWholeOldestRunsGoDownToHalf() {
        val store = SessionHistoryStore(maxBytes = 40)
        listOf("a", "b", "c", "d", "e").forEach { name ->
            store.start(name, "session")
            store.record(name, listOf(user(name.repeat(10))))
        }
        // 5 runs of 10 bytes exceed 40; the oldest go until at most 20 bytes are left, newest runs whole.
        assertEquals(listOf("d".repeat(10), "e".repeat(10)), store.messages("session").map { it.content })
    }

    @Test
    fun clearingASessionAlsoStopsARunInFlightFromWritingItBack() {
        val store = SessionHistoryStore()
        store.start("run-1", "session")
        store.record("run-1", listOf(user("一")))
        store.start("run-2", "session")
        store.clearSession("session")
        store.record("run-2", listOf(user("二")))

        assertTrue(store.messages("session").isEmpty())
    }
}

private fun user(text: String) = ModelMessage(role = "user", content = text)
private fun assistant(text: String) = ModelMessage(role = "assistant", content = text)
private fun call(id: String, name: String) =
    ModelMessage(role = "assistant", content = "", toolCallId = id, toolName = name, toolArgumentsJson = "{}")
private fun result(id: String) = ModelMessage(role = "tool", content = """{"ok":true}""", toolCallId = id)
