package cc.noharry.agent.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor

/** Optional Android calendar MCP provider backed by CalendarContract. */
class DeviceCalendarMcpProvider internal constructor(
    private val gateway: DeviceCalendarGateway,
) : McpClientProvider, ToolCatalogCacheIdentity {
    override val serverId: String = CALENDAR_SERVER_ID
    override val displayName: String = "Device Calendar"
    override val catalogCacheIdentity: String = "$CALENDAR_SERVER_ID:v2"
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    constructor(context: Context) : this(AndroidDeviceCalendarGateway(context.applicationContext))

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(calendarSearchTool(), calendarCreateTool())

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        when (toolName) {
            CALENDAR_SEARCH_EVENTS -> searchEvents(argumentsJson)
            CALENDAR_CREATE_EVENT -> createEvent(argumentsJson)
            else -> failed(DEVICE_CAPABILITY_UNAVAILABLE, "Unknown calendar tool: $toolName")
        }

    private fun searchEvents(argumentsJson: String): ToolCallResult {
        if (!gateway.canReadCalendar()) return failed(DEVICE_PERMISSION_DENIED, "Calendar read permission denied.")
        val json = parseObject(argumentsJson)
        val query = json?.stringField("query").orEmpty()
        val maxResults = json?.intField("maxResults") ?: DEFAULT_MAX_RESULTS
        return runCatching {
            val events = gateway.searchEvents(query, maxResults.coerceIn(MIN_RESULTS, MAX_RESULTS))
            ToolCallResult(true, deviceSuccessJson(""""events":[${events.joinToString(",") { it.toJson() }}]"""))
        }.getOrElse { failed(DEVICE_CALL_FAILED, it.message ?: "Calendar search failed.", retryable = true) }
    }

    private fun createEvent(argumentsJson: String): ToolCallResult {
        if (!gateway.canWriteCalendar()) return failed(DEVICE_PERMISSION_DENIED, "Calendar write permission denied.")
        val json = parseObject(argumentsJson) ?: return failed(DEVICE_ARGUMENT_INVALID, "Expected JSON object arguments.")
        val request = CalendarCreateRequest.fromJson(json) ?: return failed(DEVICE_ARGUMENT_INVALID, "Missing calendar event fields.")
        return runCatching {
            // Finding the default calendar reads the calendar list, which takes the read permission too.
            if (request.calendarId == null && !gateway.canReadCalendar()) {
                return failed(DEVICE_PERMISSION_DENIED, "Calendar read permission denied: it is needed to find the default calendar.")
            }
            val calendarId = request.calendarId ?: gateway.defaultCalendarId()
                ?: return failed(DEVICE_CAPABILITY_UNAVAILABLE, "There is no calendar on the device to add events to.")
            val uri = gateway.createEvent(request.copy(calendarId = calendarId))
            ToolCallResult(true, deviceSuccessJson(""""uri":"${uri.escapeJson()}""""))
        }.getOrElse { failed(DEVICE_CALL_FAILED, it.message ?: "Calendar create failed.", retryable = true) }
    }
}

internal interface DeviceCalendarGateway {
    fun canReadCalendar(): Boolean
    fun canWriteCalendar(): Boolean
    fun searchEvents(query: String, maxResults: Int): List<DeviceCalendarEvent>
    fun createEvent(request: CalendarCreateRequest): String

    /** The calendar an event goes to when none is named: the primary one the app may write, else the first. */
    fun defaultCalendarId(): Long?
}

internal data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
) {
    fun toJson(): String =
        """{"id":$id,"title":"${title.escapeJson()}","beginMillis":$beginMillis,"endMillis":$endMillis}"""
}

internal data class CalendarCreateRequest(
    /** Null for the device's default calendar ([DeviceCalendarGateway.defaultCalendarId]). */
    val calendarId: Long?,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
) {
    companion object {
        fun fromJson(json: kotlinx.serialization.json.JsonObject): CalendarCreateRequest? {
            // A calendarId given but not a number is a mistake, not a request for the default calendar.
            val calendarId = json.longField("calendarId")
            if (calendarId == null && json["calendarId"] != null && json["calendarId"] !is kotlinx.serialization.json.JsonNull) return null
            val title = json.stringField("title")?.takeIf { it.isNotBlank() } ?: return null
            val beginMillis = json.longField("beginMillis") ?: return null
            val endMillis = json.longField("endMillis") ?: return null
            return CalendarCreateRequest(calendarId, title, beginMillis, endMillis)
        }
    }
}

private class AndroidDeviceCalendarGateway(private val context: Context) : DeviceCalendarGateway {
    override fun canReadCalendar(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun canWriteCalendar(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun searchEvents(query: String, maxResults: Int): List<DeviceCalendarEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )
        return context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < maxResults) {
                        val title = cursor.getString(EVENT_TITLE_INDEX).orEmpty()
                        if (query.isBlank() || title.contains(query, ignoreCase = true)) {
                            add(DeviceCalendarEvent(cursor.getLong(EVENT_ID_INDEX), title, cursor.getLong(EVENT_START_INDEX), cursor.getLong(EVENT_END_INDEX)))
                        }
                    }
                }
            }.orEmpty()
    }

    override fun defaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val writable = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, writable, null, null)
            ?.use { cursor ->
                var first: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (cursor.getInt(1) == 1) return@use id
                    if (first == null) first = id
                }
                first
            }
    }

    override fun createEvent(request: CalendarCreateRequest): String {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, request.calendarId)
            put(CalendarContract.Events.TITLE, request.title)
            put(CalendarContract.Events.DTSTART, request.beginMillis)
            put(CalendarContract.Events.DTEND, request.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Calendar provider returned no URI.")
        return ContentUris.parseId(uri).toString()
    }
}

private fun calendarSearchTool(): ToolDescriptor =
    ToolDescriptor(
        name = CALENDAR_SEARCH_EVENTS,
        title = "Search calendar events",
        description = "Search device calendar events by title.",
        inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string"},"maxResults":{"type":"integer"}}}""",
    )

private fun calendarCreateTool(): ToolDescriptor =
    ToolDescriptor(
        name = CALENDAR_CREATE_EVENT,
        title = "Create calendar event",
        description = "Create a device calendar event, in the device's default calendar unless calendarId names one.",
        inputSchemaJson = """{"type":"object","required":["title","beginMillis","endMillis"],"properties":{"calendarId":{"type":"integer"},"title":{"type":"string"},"beginMillis":{"type":"integer","description":"Start, epoch milliseconds"},"endMillis":{"type":"integer","description":"End, epoch milliseconds"}}}""",
    )

private fun failed(code: String, message: String, retryable: Boolean = false): ToolCallResult =
    ToolCallResult(false, deviceErrorJson(code, message, retryable), retryable)

private const val CALENDAR_SERVER_ID = "device_calendar"
private const val CALENDAR_SEARCH_EVENTS = "calendar.search_events"
private const val CALENDAR_CREATE_EVENT = "calendar.create_event"
private const val DEFAULT_MAX_RESULTS = 10
private const val MIN_RESULTS = 1
private const val MAX_RESULTS = 50
private const val EVENT_ID_INDEX = 0
private const val EVENT_TITLE_INDEX = 1
private const val EVENT_START_INDEX = 2
private const val EVENT_END_INDEX = 3
