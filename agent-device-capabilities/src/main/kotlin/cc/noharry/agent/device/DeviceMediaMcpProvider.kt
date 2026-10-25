package cc.noharry.agent.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Optional Android media MCP provider: what plays on the device and its controls, for any app with a media session
 * (music, podcasts, video).
 *
 * With notification access granted to the host's listener ([DeviceMediaListenerService] by default; see
 * [notificationAccessGranted] and [notificationAccessIntent]) it sees every app's session: what plays and how far, the
 * actions each app offers, its own among them (a like, a play mode), and which app to control. Without access, play,
 * pause and skipping still go out as media keys, to the app that played last, unseen. The media volume needs no
 * access. Playing something by name works only in apps whose session takes a search.
 */
class DeviceMediaMcpProvider internal constructor(
    private val gateway: DeviceMediaGateway,
    private val settleMillis: Long = SETTLE_MILLIS,
) : McpClientProvider, ToolCatalogCacheIdentity {
    override val serverId: String = MEDIA_SERVER_ID
    override val displayName: String = "Device Media"
    override val catalogCacheIdentity: String = "$MEDIA_SERVER_ID:v2"
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    /** Sessions seen through [DeviceMediaListenerService], which the host declares in its manifest. */
    constructor(context: Context) : this(context, defaultListener(context))

    /** Sessions seen through the host's own notification listener [listener]. */
    constructor(context: Context, listener: ComponentName) :
        this(AndroidDeviceMediaGateway(context.applicationContext, listener))

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mediaStatusTool(), mediaControlTool(), mediaVolumeTool(), mediaPlayFromSearchTool())

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val args = parseObject(argumentsJson.ifBlank { "{}" }) ?: return failed(DEVICE_ARGUMENT_INVALID, "Expected JSON object arguments.")
        return try {
            when (toolName) {
                MEDIA_STATUS -> status()
                MEDIA_CONTROL -> control(args)
                MEDIA_VOLUME -> volume(args)
                MEDIA_PLAY_FROM_SEARCH -> playFromSearch(args)
                else -> failed(DEVICE_CAPABILITY_UNAVAILABLE, "Unknown media tool: $toolName")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed(DEVICE_CALL_FAILED, failure.message ?: "Media call failed.", retryable = true)
        }
    }

    private fun status(): ToolCallResult {
        val sessions = gateway.sessions()
        return ok {
            put("access", sessions != null)
            put("volumePercent", gateway.volumePercent())
            if (sessions == null) {
                put("note", NO_ACCESS)
                return@ok
            }
            val current = pick(sessions, null)
            if (current == null) put("note", "Nothing plays, and no app keeps a paused session.") else put("current", current.json(full = true))
            putJsonArray("others") { sessions.filter { it != current }.take(MAX_OTHERS).forEach { add(it.json(full = false)) } }
        }
    }

    private suspend fun control(args: JsonObject): ToolCallResult {
        val action = ControlAction.entries.firstOrNull { it.id == args.stringField("action") }
            ?: return failed(DEVICE_ARGUMENT_INVALID, "action must be one of ${ControlAction.entries.joinToString { it.id }}.")
        val app = args.stringField("app")?.takeIf { it.isNotBlank() }
        val sessions = gateway.sessions() ?: return byKey(action, app)
        val target = pick(sessions, app) ?: return if (app == null) {
            byKey(action, null)
        } else {
            failed(DEVICE_CAPABILITY_UNAVAILABLE, "No media session of \"$app\": open it and play something first.${sessionsNote(sessions)}")
        }
        val command = commandFor(action, target, args) ?: return refusal(action, target, args)
        if (!gateway.send(target.id, command)) return failed(DEVICE_CALL_FAILED, "${target.app} closed its media session.", retryable = true)
        val after = settle(target, command)
        return ok {
            put("did", action.id)
            put("app", target.app)
            if (after != null) put("current", after.json(full = true)) else put("note", "${target.app} closed its session after the command.")
        }
    }

    private fun commandFor(action: ControlAction, target: MediaSessionSnapshot, args: JsonObject): MediaCommand? = when (action) {
        ControlAction.PLAY -> MediaCommand.Play
        ControlAction.PAUSE -> MediaCommand.Pause
        ControlAction.TOGGLE -> if (target.playing) MediaCommand.Pause else MediaCommand.Play
        ControlAction.NEXT -> MediaCommand.Next
        ControlAction.PREVIOUS -> MediaCommand.Previous
        ControlAction.STOP -> MediaCommand.Stop
        ControlAction.SEEK -> args.longField("positionMs")?.takeIf { target.canSeek }?.let { MediaCommand.SeekTo(it.coerceAtLeast(0)) }
        ControlAction.CUSTOM -> args.stringField("customAction")?.let { customAction(target, it) }?.let { MediaCommand.Custom(it.action) }
    }

    /** Why [action] cannot go to [target] as asked (a seek or one of its own actions). */
    private fun refusal(action: ControlAction, target: MediaSessionSnapshot, args: JsonObject): ToolCallResult = when {
        action == ControlAction.SEEK && !target.canSeek -> failed(DEVICE_CAPABILITY_UNAVAILABLE, "${target.app} does not let its position be set.")
        action == ControlAction.SEEK -> failed(DEVICE_ARGUMENT_INVALID, "seek needs positionMs.")
        args.stringField("customAction").isNullOrBlank() -> failed(DEVICE_ARGUMENT_INVALID, "custom needs customAction, one of the app's own actions.")
        else -> failed(
            DEVICE_CAPABILITY_UNAVAILABLE,
            "${target.app} has no action \"${args.stringField("customAction")}\"; it offers: " +
                target.customActions.joinToString("、") { it.name }.ifEmpty { "none" } + ".",
        )
    }

    /** One of [target]'s own actions, by its id or its label, the label matched loosely (「喜欢」 for 「我喜欢」). */
    private fun customAction(target: MediaSessionSnapshot, asked: String): MediaCustomAction? =
        target.customActions.firstOrNull { it.action == asked || it.name.equals(asked, ignoreCase = true) }
            ?: target.customActions.firstOrNull { it.name.contains(asked, ignoreCase = true) || asked.contains(it.name, ignoreCase = true) }

    /** Without a session to address, the command goes out as a media key; what it reaches cannot be seen. */
    private fun byKey(action: ControlAction, app: String?): ToolCallResult {
        val key = action.keyCode ?: return failed(DEVICE_PERMISSION_DENIED, "$NO_ACCESS ${action.id} needs it.")
        gateway.mediaKey(key)
        return ok {
            put("did", action.id)
            put("via", "media_key")
            put(
                "note",
                "Sent as a media key to the app that played last; whether it took it cannot be seen" +
                    (if (app != null) ", nor \"$app\" picked" else "") + ": tell the user the key was sent, not that it plays.",
            )
        }
    }

    private fun volume(args: JsonObject): ToolCallResult {
        when (args.stringField("action")) {
            "get" -> Unit
            "up" -> gateway.adjustVolume(raise = true)
            "down" -> gateway.adjustVolume(raise = false)
            "set" -> gateway.setVolume(args.intField("percent")?.coerceIn(0, FULL) ?: return failed(DEVICE_ARGUMENT_INVALID, "set needs percent, 0–100."))
            else -> return failed(DEVICE_ARGUMENT_INVALID, "action must be get, up, down or set.")
        }
        return ok { put("volumePercent", gateway.volumePercent()) }
    }

    private suspend fun playFromSearch(args: JsonObject): ToolCallResult {
        val query = args.stringField("query")?.takeIf { it.isNotBlank() } ?: return failed(DEVICE_ARGUMENT_INVALID, "Missing query.")
        val app = args.stringField("app")?.takeIf { it.isNotBlank() }
        val sessions = gateway.sessions() ?: return failed(DEVICE_PERMISSION_DENIED, "$NO_ACCESS Playing by name needs it.")
        val target = if (app != null) pick(sessions, app) else sessions.firstOrNull { it.playing && it.canPlayFromSearch } ?: sessions.firstOrNull { it.canPlayFromSearch }
        if (target == null || !target.canPlayFromSearch) {
            val able = sessions.filter { it.canPlayFromSearch }.joinToString("、") { it.app }
            val who = target?.app ?: app ?: "No app open now"
            return failed(
                DEVICE_CAPABILITY_UNAVAILABLE,
                "$who cannot play by name through its media session" + (if (able.isNotEmpty()) " ($able can)" else "") +
                    ": open the app and pick it on screen.",
            )
        }
        val command = MediaCommand.PlayFromSearch(query)
        if (!gateway.send(target.id, command)) return failed(DEVICE_CALL_FAILED, "${target.app} closed its media session.", retryable = true)
        val after = settle(target, command)
        return ok {
            put("did", "play_from_search")
            put("app", target.app)
            after?.let { put("current", it.json(full = true)) }
        }
    }

    /** The session asked for by [app], else any; of those, the one playing, else the most recently active. */
    private fun pick(sessions: List<MediaSessionSnapshot>, app: String?): MediaSessionSnapshot? {
        val pool = if (app == null) sessions else sessions.filter { it.matches(app) }
        return pool.firstOrNull { it.playing } ?: pool.firstOrNull()
    }

    /** The app takes a moment to play, pause or change the track: waited for, up to [settleMillis]. */
    private suspend fun settle(before: MediaSessionSnapshot, command: MediaCommand): MediaSessionSnapshot? {
        var seen = gateway.sessions()?.firstOrNull { it.id == before.id }
        var waited = 0L
        while (seen != null && !settled(before, seen, command) && waited < settleMillis) {
            delay(SETTLE_POLL_MILLIS)
            waited += SETTLE_POLL_MILLIS
            seen = gateway.sessions()?.firstOrNull { it.id == before.id }
        }
        return seen
    }

    private fun settled(before: MediaSessionSnapshot, now: MediaSessionSnapshot, command: MediaCommand): Boolean = when (command) {
        MediaCommand.Play, is MediaCommand.PlayFromSearch -> now.playing
        MediaCommand.Pause, MediaCommand.Stop -> !now.playing
        MediaCommand.Next, MediaCommand.Previous -> now.title != before.title
        is MediaCommand.SeekTo, is MediaCommand.Custom -> true
    }

    private fun sessionsNote(sessions: List<MediaSessionSnapshot>): String =
        if (sessions.isEmpty()) "" else " Sessions now: ${sessions.joinToString("、") { it.app }}."

    companion object {
        /** Whether the user granted notification access to [listener], [DeviceMediaListenerService] unless the host has its own. */
        @JvmStatic
        @JvmOverloads
        fun notificationAccessGranted(context: Context, listener: ComponentName = defaultListener(context)): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == listener }
        }

        /** The system page where the user grants notification access. */
        @JvmStatic
        fun notificationAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

/** What media.control can do; [keyCode] the media key that does it without a session, if there is one. */
internal enum class ControlAction(val id: String, val keyCode: Int?) {
    PLAY("play", KEYCODE_MEDIA_PLAY),
    PAUSE("pause", KEYCODE_MEDIA_PAUSE),
    TOGGLE("toggle", KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT("next", KEYCODE_MEDIA_NEXT),
    PREVIOUS("previous", KEYCODE_MEDIA_PREVIOUS),
    STOP("stop", KEYCODE_MEDIA_STOP),
    SEEK("seek", null),
    CUSTOM("custom", null),
}

private fun MediaSessionSnapshot.json(full: Boolean): JsonObject = buildJsonObject {
    put("app", app)
    put("package", packageName)
    put("state", state)
    put("title", title)
    if (artist.isNotBlank()) put("artist", artist)
    if (!full) return@buildJsonObject
    if (album.isNotBlank()) put("album", album)
    positionMs?.let { put("positionMs", it) }
    durationMs?.let { put("durationMs", it) }
    put("canSeek", canSeek)
    put("canPlayFromSearch", canPlayFromSearch)
    putJsonArray("customActions") { customActions.forEach { add(it.name) } }
}

private fun ok(fields: JsonObjectBuilder.() -> Unit): ToolCallResult =
    ToolCallResult(true, buildJsonObject { put("ok", true); fields() }.toString())

private fun failed(code: String, message: String, retryable: Boolean = false): ToolCallResult =
    ToolCallResult(false, deviceErrorJson(code, message, retryable), retryable)

private fun defaultListener(context: Context): ComponentName = ComponentName(context, DeviceMediaListenerService::class.java)

private fun mediaStatusTool(): ToolDescriptor = ToolDescriptor(
    name = MEDIA_STATUS,
    title = "Media status",
    description = "What plays on the device: the app, title, artist, playing or paused, position, what the app lets you " +
        "do (seek, play by name, its own actions) and the media volume; other apps with a media session too. " +
        "现在在放什么歌、音乐、播客、视频。",
    inputSchemaJson = """{"type":"object","properties":{}}""",
)

private fun mediaControlTool(): ToolDescriptor = ToolDescriptor(
    name = MEDIA_CONTROL,
    title = "Control media",
    description = "Play, pause, toggle, skip to the next or previous track, stop or seek what plays (music, podcasts, " +
        "video), or run one of the app's own actions (custom, e.g. a like or a play mode, as media.status lists them). " +
        "app picks the app (its name or package); without it the one playing, else the latest. It cannot pick a song: " +
        "use media.play_from_search where the app allows it, else open the app and choose on screen. " +
        "音乐播放控制：播放、继续、暂停、下一首、上一首、切歌、停止、快进、喜欢、收藏、播放模式。",
    inputSchemaJson = """{"type":"object","required":["action"],"properties":{""" +
        """"action":{"type":"string","enum":["play","pause","toggle","next","previous","stop","seek","custom"]},""" +
        """"app":{"type":"string","description":"The app to control, e.g. 网易云音乐 or its package name"},""" +
        """"positionMs":{"type":"integer","minimum":0,"description":"seek: where to, in milliseconds"},""" +
        """"customAction":{"type":"string","description":"custom: the action's name as media.status lists it"}}}""",
)

private fun mediaVolumeTool(): ToolDescriptor = ToolDescriptor(
    name = MEDIA_VOLUME,
    title = "Media volume",
    description = "The device's media volume: get it, turn it up or down a step, or set it to percent. 媒体音量、声音大小。",
    inputSchemaJson = """{"type":"object","required":["action"],"properties":{""" +
        """"action":{"type":"string","enum":["get","up","down","set"]},""" +
        """"percent":{"type":"integer","minimum":0,"maximum":100,"description":"set: the volume, 0–100"}}}""",
)

private fun mediaPlayFromSearchTool(): ToolDescriptor = ToolDescriptor(
    name = MEDIA_PLAY_FROM_SEARCH,
    title = "Play by name",
    description = "Ask an app to find and play something by name (a song, an artist, a podcast), through its media " +
        "session. Only apps that allow it (canPlayFromSearch in media.status); others refuse, and then the song is " +
        "picked in the app on screen. 点歌、放某首歌、放某个歌手。",
    inputSchemaJson = """{"type":"object","required":["query"],"properties":{""" +
        """"query":{"type":"string","description":"What to play, e.g. 周杰伦 晴天"},""" +
        """"app":{"type":"string","description":"The app to ask; without it one that allows it"}}}""",
)

private const val MEDIA_SERVER_ID = "device_media"
private const val MEDIA_STATUS = "media.status"
private const val MEDIA_CONTROL = "media.control"
private const val MEDIA_VOLUME = "media.volume"
private const val MEDIA_PLAY_FROM_SEARCH = "media.play_from_search"
private const val NO_ACCESS = "Notification access is not granted, so other apps' media sessions cannot be seen."
private const val ENABLED_LISTENERS = "enabled_notification_listeners"
private const val MAX_OTHERS = 5
private const val FULL = 100
private const val SETTLE_MILLIS = 2_000L
private const val SETTLE_POLL_MILLIS = 100L

// android.view.KeyEvent's media keys, as plain values so the action list needs no Android class to load.
private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
private const val KEYCODE_MEDIA_STOP = 86
private const val KEYCODE_MEDIA_NEXT = 87
private const val KEYCODE_MEDIA_PREVIOUS = 88
private const val KEYCODE_MEDIA_PLAY = 126
private const val KEYCODE_MEDIA_PAUSE = 127
