package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatToolCallReminderTest {
    @Test
    fun theThirdIdenticalCallGetsTheGentleReminderAndTheFifthNamesTheCall() {
        val reminder = RepeatToolCallReminder()
        val reminders = (1..5).map { reminder.after(round(call("search_tools", """{"query":"空调","limit":5}"""))) }

        assertEquals(listOf(null, null), reminders.take(2))
        assertTrue(reminders[2]!!.startsWith("You are repeating the exact same tool call"))
        assertNull(reminders[3])
        assertTrue(reminders[4]!!.contains("- tool: search_tools\n- consecutive_calls: 5"))
    }

    @Test
    fun keyOrderDoesNotMatterButAnyOtherCallStartsTheCountAgain() {
        val reminder = RepeatToolCallReminder()
        reminder.after(round(call("set", """{"a":1,"b":2}""")))
        reminder.after(round(call("set", """{"b":2,"a":1}""")))
        assertTrue(reminder.after(round(call("set", """{"a":1,"b":2}"""))) != null)

        reminder.after(round(call("set", """{"a":2,"b":2}""")))
        reminder.after(round(call("set", """{"a":1,"b":2}""")))
        assertNull(reminder.after(round(call("set", """{"a":1,"b":2}"""))))
    }

    @Test
    fun toolResultsAndTextDoNotCount() {
        val reminder = RepeatToolCallReminder()
        val result = ModelMessage(role = "tool", content = "{}", toolCallId = "c", toolName = "set")

        repeat(4) { assertNull(reminder.after(listOf(result, ModelMessage(role = "assistant", content = "好的")))) }
    }

    private fun call(name: String, arguments: String) =
        ModelMessage(role = "assistant", content = "", toolCallId = "c", toolName = name, toolArgumentsJson = arguments)

    private fun round(call: ModelMessage) = listOf(call, ModelMessage(role = "tool", content = "{}", toolCallId = "c", toolName = call.toolName))
}
