package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.ThreadEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a run says aloud as it goes: from [VoicePort], which says it or hands it on as text. */
interface RunAnnouncer {
    /** Whether something is being said or waits its turn. */
    val busy: Boolean

    /** Run [runScope]'s [event]: its progress lines, its reply, a question it asks; [ownsUi] whether it is on screen. */
    fun observe(runScope: RuntimeRunScope, event: ThreadEvent, ownsUi: (RuntimeRunScope) -> Boolean)

    /** Says [text] after what is queued; false when there is no voice to say it. */
    fun announce(text: String): Boolean

    /** Says [text] before anything queued (a voice append's acknowledgement); false when there is no voice. */
    fun announceFirst(text: String): Boolean

    /** Text another agent's task [taskId] streams to be said (an A2A task's), as it comes. */
    fun speakWireSpeech(taskId: String, text: String, final: Boolean)

    fun stop()
}

/**
 * The host's voice as the runtime uses it: announcers that say runs aloud, the rules for what a run says, and the
 * session around them. An app without a voice keeps [NONE]: runs say nothing and go on as they do on screen.
 */
interface VoicePort {
    /** An announcer that speaks on this device; [followsSwitch] when the 播报 switch decides. Null without a voice. */
    fun deviceAnnouncer(context: Context, scope: CoroutineScope, followsSwitch: Boolean = true, onStatus: (String) -> Unit = {}): RunAnnouncer? =
        null

    /** An announcer that hands what it would say to [output] instead (the spoken text of another agent's task). */
    fun textAnnouncer(scope: CoroutineScope, output: (text: String, final: Boolean) -> Unit): RunAnnouncer? = null

    /** Run [requestId]'s [event]: [say] it, hand it to another voice, or nothing when the run was silenced. */
    fun speak(event: ThreadEvent, requestId: String, owns: () -> Boolean, say: () -> Unit) = say()

    /** The card's X: run [requestId] goes on, silently. */
    fun silence(requestId: String) = Unit

    /** Whether the voice is saying something now. */
    val active: StateFlow<Boolean> get() = SILENT

    /** Whether speech is set up (a key and a voice), loading its settings first when they are not yet. */
    suspend fun ready(context: Context): Boolean = false

    /** Its settings were saved: from the next line on. */
    fun refresh(context: Context) = Unit

    /** Something came on its own that the person should notice (a plan due): the voice's chime, before it shows. */
    fun chime() = Unit

    /** A report delivered at [since] was answered aloud: a reply is heard without the wake word, unless voice was closed. */
    fun listenAfterReport(context: Context, since: Long) = Unit

    companion object {
        val NONE: VoicePort = object : VoicePort {}
        private val SILENT: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }
}

/** The voice the runtime speaks with: the host's ([install]), or none. */
internal object RuntimeVoice {
    @Volatile
    var port: VoicePort = VoicePort.NONE
        private set

    fun install(port: VoicePort) {
        this.port = port
    }
}
