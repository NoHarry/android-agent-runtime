package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.OUTSIDE_CONTEXT_TURNS
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.session.BACKEND_LIVE

/**
 * The conversation on screen, as a plugin starts and stops its turns ([PluginContext.turns]): the host's voice, a
 * debug door, a companion that answers some turns itself. Main thread.
 */
@AppServerPluginApi
interface Turns {
    /** Whether the conversation on screen has a run going. */
    val running: Boolean

    /**
     * Starts [text] as a turn of the conversation on screen. [spoken]: the person said it (its progress and answer are
     * spoken, and it counts as the person's voice turn). [modelText] is what the model reads in place of [text], which
     * the transcript shows. [live] leaves the simulated backend for the selected model profile first; [stream] turns
     * streaming on or off from now on (a bench's choice); null leaves it.
     */
    fun start(text: String, spoken: Boolean = false, modelText: String? = null, live: Boolean = false, stream: Boolean? = null)

    /** Stops the run going now, if any, then starts [text] as a spoken turn ([start]'s [live] and [stream]). */
    fun replace(text: String, live: Boolean = true, stream: Boolean? = null)

    /** Stops the run going now in the conversation on screen; the conversation stays. */
    fun stop()

    /**
     * Follows the conversation on screen: [listener] gets the runtime's state now and after every change, on the main
     * thread, until the returned function is called.
     */
    fun watch(listener: (ServerState) -> Unit): () -> Unit

    /** Starts a new conversation, which becomes the one on screen. */
    fun newConversation()

    /**
     * Starts a new conversation when the one on screen has been quiet long enough (the person's session settings),
     * as a wake does; nothing while a run goes or work it started is unfinished. True when it started one.
     */
    fun newConversationIfQuiet(): Boolean

    /** The latest exchanges of the conversation on screen (the person's words, the answer), oldest first. */
    suspend fun recentExchanges(limit: Int = OUTSIDE_CONTEXT_TURNS): List<Pair<String, String>>

    /**
     * A turn something else answered (a companion, a voice service) kept as one of the conversation's: shown on the
     * card and in the transcript, read by the agent in later turns, its [reply] said in the host's voice when [speak].
     * [label] names who answered, for the status line.
     */
    fun record(words: String, reply: String, label: String, speak: Boolean = true)

    /** A check of the selected model service's own web search with [text], its result shown in the transcript (a bench's). */
    fun checkHostedSearch(text: String, stream: Boolean = false)

    /** A line of the app's own in the conversation on screen, not a turn (an app connected, say). Any thread. */
    fun note(text: String)
}

/** Speech on this device, as the host's voice plugin hears and speaks ([PluginContext.speech]). Main thread. */
@AppServerPluginApi
interface Speech {
    /** Whether the assistant's own announcer is speaking (not a voice service that speaks for itself). */
    val speaking: Boolean

    /**
     * Words the person said: added to the running turn as [forRun] (the transcript shows [text]), or, when nothing runs,
     * [text] as a new spoken turn, the model reading [modelText] when given; [onNewTurn] says it became one.
     */
    fun heard(text: String, forRun: String = text, modelText: String? = null, onNewTurn: () -> Unit = {})

    /** Says [text] before anything queued (the acknowledgement of words added to a run); false when there is no voice. */
    fun announceFirst(text: String): Boolean

    /** Says [text] once the voice's settings are read, to try them; [done] gets false when there is no voice to say it. */
    fun preview(text: String, done: (Boolean) -> Unit)

    /** The run going on now in the conversation on screen finishes without speaking (the card was closed). */
    fun silence()
}

/**
 * The assistant on screen: the client presenting it on this device (the app's overlay), as a plugin asks for it
 * ([PluginContext.assistant]). With no presenting client every request answers "not presented" at once. The
 * callbacks come on the main thread.
 */
@AppServerPluginApi
interface Assistant {
    /** Whether the assistant is up and takes wakes. */
    val ready: Boolean

    /** Whether a full-screen front end is in front, which takes a wake itself. */
    val inFront: Boolean

    /** Brings the assistant up (card closed) when it should rest on this screen; [done] gets why not, null when up. */
    fun ensureRunning(done: (String?) -> Unit = {})

    /** Starts the assistant, with its card [open]; [done] gets why not, null when started. */
    fun start(open: Boolean, done: (String?) -> Unit = {})

    /** Opens or folds the card: null when done (or held for later), else why it could not be. */
    suspend fun card(command: OverlayCommand): String?

    /** Hands a wake to the assistant, with how it came ([origin], in the host's own words); [taken] false when none is up. */
    fun wake(origin: String, taken: (Boolean) -> Unit)

    /** A request to the assistant on screen: its answer, or null when none is up or it would not answer. */
    suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R?

    /** A notification to the assistant on screen (a notice to show); nothing when none is up. */
    fun <P> tell(method: NotificationMethod<P>, params: P)
}

/** [Turns] over the server's runtime. */
internal class ServerTurns(private val app: Context) : Turns {
    override val running: Boolean get() = RuntimeSession.isRunning

    override fun start(text: String, spoken: Boolean, modelText: String?, live: Boolean, stream: Boolean?) {
        RuntimeSession.submitText(app, text, backend(live), stream?.toString(), spoken, modelText)
    }

    override fun replace(text: String, live: Boolean, stream: Boolean?) =
        RuntimeSession.submitTextReplacingRun(app, text, backend(live), stream?.toString())

    override fun stop() {
        RuntimeSession.stopRun()
    }

    override fun watch(listener: (ServerState) -> Unit): () -> Unit = RuntimeSession.attach(app, listener)

    override fun newConversation() = RuntimeSession.newSession(app)

    override fun newConversationIfQuiet(): Boolean = RuntimeSession.freshSessionForWake(app)

    override suspend fun recentExchanges(limit: Int): List<Pair<String, String>> = RuntimeSession.recentExchanges(app, limit)

    override fun record(words: String, reply: String, label: String, speak: Boolean) =
        RuntimeSession.recordCompanionTurn(app, words, reply, label, speak)

    override fun checkHostedSearch(text: String, stream: Boolean) = RuntimeSession.validateHostedSearch(app, text, stream.toString())

    override fun note(text: String) = RuntimeSession.noteConnection(text)

    private fun backend(live: Boolean): String? = if (live) BACKEND_LIVE else null
}

/** [Speech] over the server's runtime and the host's voice. */
internal class ServerSpeech(private val app: Context) : Speech {
    override val speaking: Boolean get() = RuntimeSession.speaking

    override fun heard(text: String, forRun: String, modelText: String?, onNewTurn: () -> Unit) =
        RuntimeSession.postSpokenInput(app, text, forRun, modelText, onNewTurn)

    override fun announceFirst(text: String): Boolean = RuntimeSession.announceFirst(app, text)

    override fun preview(text: String, done: (Boolean) -> Unit) = RuntimeSession.previewTts(app, text, done)

    override fun silence() = RuntimeSession.silenceRunning()
}

/** [Assistant] as the server reaches its presenting client. */
internal object ServerAssistant : Assistant {
    override val ready: Boolean get() = Presenter.ready()

    override val inFront: Boolean get() = Presenter.inFront()

    override fun ensureRunning(done: (String?) -> Unit) = Presenter.ensureRunning(done)

    override fun start(open: Boolean, done: (String?) -> Unit) = Presenter.start(open, done)

    override suspend fun card(command: OverlayCommand): String? = Presenter.card(command)

    override fun wake(origin: String, taken: (Boolean) -> Unit) = Presenter.wake(origin, taken)

    override suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R? = Presenter.ask(method, params)

    override fun <P> tell(method: NotificationMethod<P>, params: P) = Presenter.tell(method, params)
}
