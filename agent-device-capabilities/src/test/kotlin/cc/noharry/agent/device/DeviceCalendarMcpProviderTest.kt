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
}

private class FakeCalendarGateway(
    private val canRead: Boolean = true,
    private val canWrite: Boolean = true,
) : DeviceCalendarGateway {
    override fun canReadCalendar(): Boolean = canRead
    override fun canWriteCalendar(): Boolean = canWrite

    override fun searchEvents(query: String, maxResults: Int): List<DeviceCalendarEvent> =
        listOf(DeviceCalendarEvent(1, "Design sync", 10, 20))

    override fun createEvent(request: CalendarCreateRequest): String =
        "content://calendar/events/${request.calendarId}"
}

private fun callContext(): ToolCallContext =
    ToolCallContext(sessionId = "session-1", runId = "run-1", requestId = "request-1")
