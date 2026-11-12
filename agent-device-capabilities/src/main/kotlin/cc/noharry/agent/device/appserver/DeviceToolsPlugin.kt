package cc.noharry.agent.device.appserver

import android.content.Context
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PluginToolServer
import cc.noharry.agent.appserver.server.foregroundApp
import cc.noharry.agent.device.DeviceCalendarMcpProvider
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.agent.device.DeviceNotificationMcpProvider
import cc.noharry.agent.device.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The device's own tools on the app server (id `device-tools`): its calendar and its notifications (off until the person
 * switches them on, as they ask for permissions) and its media (playback and volume, on at first), each a tool server
 * in the MCP list. An app that depends on both this module and `agent-app-server` gets it without listing it: this
 * module's manifest declares it, and the server loads it from there
 * ([cc.noharry.agent.appserver.server.PluginDiscovery]); `AppServerConfig.ignoredPlugins` leaves it out. Nothing else
 * in this module names it, so an app without the server never loads it.
 *
 * While it runs it offers [ServerFeatures.DEVICE_MEDIA]. A pause with nothing playing goes to the app in front when a
 * plugin that sees the screen names it ([foregroundApp]). Reading the media volume asks no one ([mediaCallActs]). The
 * rows' titles are this module's string resources (`device_tools_*`), which an app replaces with its own.
 */
@ExperimentalAppServerApi
object DeviceToolsPlugin : AppServerPlugin {
    override val id: String = "device-tools"

    override fun apply(ctx: PluginContext) {
        ctx.offer(ServerFeatures.DEVICE_MEDIA)
        ctx.toolServers(listOf(CALENDAR, NOTIFICATIONS, MEDIA))
        ctx.ownToolServer(DEVICE_MEDIA_TOOL_SERVER, worksDevice = true, acts = ::mediaCallActs)
    }
}

/**
 * Whether a call of the media tool server acts, where its tool's `effectKind` cannot say: `media.volume` says it acts, as
 * it turns the volume up, down or to a level, while `{"action": "get"}` only reads it. Null for every other call, which
 * asks as its tool says (`media.status` only reads; playing, pausing and the rest act).
 */
internal fun mediaCallActs(tool: String, arguments: String): Boolean? =
    if (tool == MEDIA_VOLUME_TOOL && arguments.action() == READ_VOLUME) false else null

/** The `action` the call's arguments give, exactly as the tool reads it; null when they give none. */
private fun String.action(): String? =
    (runCatching { Json.parseToJsonElement(this) }.getOrNull() as? JsonObject)?.get("action")
        ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content

private const val MEDIA_VOLUME_TOOL = "media.volume"
private const val READ_VOLUME = "get"

/** The device's calendar as a tool server (`mcp__device_calendar__…`), and its row's id in the MCP list. */
@ExperimentalAppServerApi
const val DEVICE_CALENDAR_TOOL_SERVER = "device_calendar"

/** The device's notifications as a tool server (`mcp__device_notification__…`), and its row's id in the MCP list. */
@ExperimentalAppServerApi
const val DEVICE_NOTIFICATIONS_TOOL_SERVER = "device_notification"

/** The device's media as a tool server (`mcp__device_media__…`), and its row's id in the MCP list. */
@ExperimentalAppServerApi
const val DEVICE_MEDIA_TOOL_SERVER = "device_media"

private val CALENDAR = PluginToolServer(
    id = DEVICE_CALENDAR_TOOL_SERVER,
    label = "Device Calendar",
    title = { it.getString(R.string.device_tools_calendar) },
) { context, wrap -> wrap(DeviceCalendarMcpProvider(context)) }

private val NOTIFICATIONS = PluginToolServer(
    id = DEVICE_NOTIFICATIONS_TOOL_SERVER,
    label = "Device Notifications",
    title = { it.getString(R.string.device_tools_notifications) },
) { context, wrap -> wrap(DeviceNotificationMcpProvider(context, channelName = appLabel(context))) }

// On from the start: media keys and the volume need no permission; notification access adds what plays and which app.
private val MEDIA = PluginToolServer(
    id = DEVICE_MEDIA_TOOL_SERVER,
    label = "Device Media",
    title = { it.getString(R.string.device_tools_media) },
    onAtFirst = true,
    worksDevice = true,
    notificationAccess = { DeviceMediaMcpProvider.notificationAccessGranted(it) },
) { context, wrap ->
    // The app in front, so a pause with nothing playing goes to what is on screen (a video app's session reads stopped).
    val media = DeviceMediaMcpProvider(context, foregroundPackage = { foregroundApp(context)?.packageName })
    MediaToolHints(wrap(media))
}

private fun appLabel(context: Context): String = context.applicationInfo.loadLabel(context.packageManager).toString()
