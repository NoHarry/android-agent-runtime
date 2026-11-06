package cc.noharry.agent.device

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCalendarMcpProviderTest {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresSerializedConcurrency() {
        assertEquals(
            ProviderConcurrencyDeclaration.serialized(),
            DeviceCalendarMcpProvider(FakeCalendarGateway()).providerConcurrency,
        )
    }

    @Test
    fun exposesCalendarTools() = runBlocking {
        val tools = DeviceCalendarMcpProvider(FakeCalendarGateway()).listTools().map { it.name }

        assertEquals(listOf("calendar.search_events", "calendar.create_event"), tools)
    }

    @Test
    fun searchEventsUsesGateway() = runBlocking {
        val result = DeviceCalendarMcpProvider(FakeCalendarGateway()).callTool(
            "calendar.search_events",
            """{"query":"sync","maxResults":"5"}""",
            callContext(),
        )

        assertTrue(result.success)
        assertTrue(result.resultJson.contains("Design sync"))
    }

    @Test
    fun deniedPermissionReturnsStructuredError() = runBlocking {
        val result = DeviceCalendarMcpProvider(FakeCalendarGateway(canRead = false)).callTool(
            "calendar.search_events",
            "{}",
            callContext(),
        )

        assertFalse(result.success)
        assertTrue(result.resultJson.contains(DEVICE_PERMISSION_DENIED))
    }

    @Test
    fun createEventUsesGateway() = runBlocking {
        val result = DeviceCalendarMcpProvider(FakeCalendarGateway()).callTool(
            "calendar.create_event",
            """{"calendarId":"1","title":"Review","beginMillis":"10","endMillis":"20"}""",
            callContext(),
        )

        assertTrue(result.success)
        assertTrue(result.resultJson.contains("content://calendar/events/1"))
    }

    @Test
    fun anEventWithoutACalendarGoesToTheDefaultOne() = runBlocking {
        val event = """{"title":"Review","beginMillis":10,"endMillis":20}"""
        val made = DeviceCalendarMcpProvider(FakeCalendarGateway()).callTool("calendar.create_event", event, callContext())
        assertTrue(made.resultJson.contains("content://calendar/events/42"))
        val none = DeviceCalendarMcpProvider(FakeCalendarGateway(defaultCalendar = null)).callTool("calendar.create_event", event, callContext())
        assertFalse(none.success)
        assertTrue(none.resultJson.contains(DEVICE_CAPABILITY_UNAVAILABLE))
        // Finding it reads the calendar list; a named calendar is not looked up; a calendarId that is no number is a mistake.
        val writeOnly = DeviceCalendarMcpProvider(FakeCalendarGateway(canRead = false)).callTool("calendar.create_event", event, callContext())
        assertTrue(writeOnly.resultJson.contains(DEVICE_PERMISSION_DENIED))
        val gateway = FakeCalendarGateway()
        val named = DeviceCalendarMcpProvider(gateway).callTool("calendar.create_event", """{"calendarId":7,"title":"Review","beginMillis":10,"endMillis":20}""", callContext())
        assertTrue(named.resultJson.contains("content://calendar/events/7"))
        assertEquals(0, gateway.defaultLookups)
        val wrong = DeviceCalendarMcpProvider(gateway).callTool("calendar.create_event", """{"calendarId":"work","title":"Review","beginMillis":10,"endMillis":20}""", callContext())
        assertTrue(wrong.resultJson.contains(DEVICE_ARGUMENT_INVALID))
    }
}

private class FakeCalendarGateway(
    private val canRead: Boolean = true,
    private val canWrite: Boolean = true,
    private val defaultCalendar: Long? = 42,
) : DeviceCalendarGateway {
    var defaultLookups = 0

    override fun defaultCalendarId(): Long? = defaultCalendar.also { defaultLookups += 1 }

    override fun canReadCalendar(): Boolean = canRead
    override fun canWriteCalendar(): Boolean = canWrite

    override fun searchEvents(query: String, maxResults: Int): List<DeviceCalendarEvent> =
        listOf(DeviceCalendarEvent(1, "Design sync", 10, 20))

    override fun createEvent(request: CalendarCreateRequest): String =
        "content://calendar/events/${request.calendarId}"
}

private fun callContext(): ToolCallContext =
    ToolCallContext(sessionId = "session-1", runId = "run-1", requestId = "request-1")
