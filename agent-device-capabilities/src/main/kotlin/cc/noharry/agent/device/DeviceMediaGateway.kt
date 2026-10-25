package cc.noharry.agent.device

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/**
 * The notification listener whose access lets [DeviceMediaMcpProvider] see other apps' media sessions; it reads no
 * notification. The host declares it in its manifest, guarded by `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`
 * with the `android.service.notification.NotificationListenerService` action, and the user grants it notification
 * access ([DeviceMediaMcpProvider.notificationAccessIntent]).
 */
open class DeviceMediaListenerService : NotificationListenerService()

/** One app's media session as last seen: what it plays, how far, and what it can be asked to do. */
internal data class MediaSessionSnapshot(
    val id: String,
    val packageName: String,
    val app: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    /** playing, paused, stopped, buffering, none or other. */
    val state: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val canPlayFromSearch: Boolean = false,
    /** The app's own actions (a like, a play mode), by the label it gives them. */
    val customActions: List<MediaCustomAction> = emptyList(),
) {
    val playing: Boolean get() = state == "playing"

    /** Whether [app] names this session's app: its package, or its label either way round. */
    fun matches(app: String): Boolean =
        packageName.equals(app, ignoreCase = true) ||
            (this.app.isNotBlank() && (this.app.contains(app, ignoreCase = true) || app.contains(this.app, ignoreCase = true)))
}

internal data class MediaCustomAction(val action: String, val name: String)

/** What a session is asked to do. */
internal sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data object Stop : MediaCommand
    data class SeekTo(val positionMs: Long) : MediaCommand
    data class Custom(val action: String) : MediaCommand
    data class PlayFromSearch(val query: String) : MediaCommand
}

/** The device's media, as [DeviceMediaMcpProvider] reaches it. */
internal interface DeviceMediaGateway {
    /** The active sessions, most recently active first; null without notification access. */
    fun sessions(): List<MediaSessionSnapshot>?

    /** Sends [command] to the session [sessionId]; false when that session is gone. */
    fun send(sessionId: String, command: MediaCommand): Boolean

    /** A media key ([KeyEvent] code), to whichever app the system routes media keys to. */
    fun mediaKey(keyCode: Int)

    fun volumePercent(): Int

    /** Whether any app is sounding through the media stream, whatever the sessions say. */
    fun soundPlaying(): Boolean

    fun adjustVolume(raise: Boolean)

    fun setVolume(percent: Int)
}

internal class AndroidDeviceMediaGateway(
    private val context: Context,
    private val listener: ComponentName,
) : DeviceMediaGateway {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val manager: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)
    @Volatile private var controllers: Map<String, MediaController> = emptyMap()

    override fun sessions(): List<MediaSessionSnapshot>? {
        val active = try {
            manager?.getActiveSessions(listener) ?: return null
        } catch (_: SecurityException) {
            return null
        }
        val seen = LinkedHashMap<String, MediaController>()
        val snapshots = active.map { controller ->
            val id = "${controller.packageName}:${controller.sessionToken.hashCode()}"
            seen[id] = controller
            controller.snapshot(id)
        }
        controllers = seen
        return snapshots
    }

    override fun send(sessionId: String, command: MediaCommand): Boolean {
        val controller = controllers[sessionId] ?: sessions()?.let { controllers[sessionId] } ?: return false
        val controls = controller.transportControls
        when (command) {
            MediaCommand.Play -> controls.play()
            MediaCommand.Pause -> controls.pause()
            MediaCommand.Next -> controls.skipToNext()
            MediaCommand.Previous -> controls.skipToPrevious()
            MediaCommand.Stop -> controls.stop()
            is MediaCommand.SeekTo -> controls.seekTo(command.positionMs)
            is MediaCommand.Custom -> controls.sendCustomAction(command.action, null)
            is MediaCommand.PlayFromSearch -> controls.playFromSearch(command.query, null)
        }
        return true
    }

    override fun mediaKey(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun soundPlaying(): Boolean = audio.isMusicActive

    override fun volumePercent(): Int =
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun adjustVolume(raise: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    override fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + HALF_PERCENT) / FULL_PERCENT, 0)
    }

    private fun MediaController.snapshot(id: String): MediaSessionSnapshot {
        val playback = playbackState
        val meta = metadata
        val actions = playback?.actions ?: 0L
        return MediaSessionSnapshot(
            id = id,
            packageName = packageName,
            app = label(packageName),
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            state = stateName(playback?.state),
            positionMs = playback?.let(::positionNow),
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canPlayFromSearch = actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L,
            customActions = playback?.customActions.orEmpty().map { MediaCustomAction(it.action, it.name.toString()) },
        )
    }

    /** Where playback is now: the session reports where it was at its last update, and moves on from there while playing. */
    private fun positionNow(playback: PlaybackState): Long? {
        if (playback.position < 0) return null
        if (playback.state != PlaybackState.STATE_PLAYING) return playback.position
        val since = SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime
        return playback.position + (since * playback.playbackSpeed).toLong()
    }

    /** The app's name, else its package: other apps are visible through the launcher query in this module's manifest. */
    private fun label(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        PlaybackState.STATE_NONE, null -> "none"
        else -> "other"
    }

    private companion object {
        const val HALF_PERCENT = 50
        const val FULL_PERCENT = 100
    }
}
