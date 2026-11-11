package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PresentCardParams
import cc.noharry.agent.appserver.protocol.PresentResult
import cc.noharry.agent.appserver.protocol.PresentStartParams
import cc.noharry.agent.appserver.protocol.PresentWakeParams
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.PresentationState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The client presenting the assistant on this device (the app's overlay), as [hub] reaches it over the protocol: how
 * it last said it stands, and requests and notifications for it. Without a presenting client every ask answers as
 * "not presented" at once. [changed] hears the assistant coming up or going.
 */
internal class PresentationLink(private val hub: ServerHub) {
    @Volatile var state = PresentationState()
        private set

    @Volatile var changed: (ready: Boolean) -> Unit = {}

    /** The presenting client said how the assistant stands now. Main thread. */
    fun reported(next: PresentationState) {
        val was = state
        state = next
        if (was.ready != next.ready) changed(next.ready)
    }

    /** The answer to [method], or null when no client presents or it would not answer. */
    suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R? {
        val presenter = hub.presenter() ?: return null
        return try {
            presenter.request(method, params)
        } catch (refused: JsonRpcException) {
            null
        }
    }

    internal fun <P> tell(method: NotificationMethod<P>, params: P) {
        hub.presenter()?.notify(method, params)
    }

    /** What a presenting ask came to: null when done, else why not; a screen that keeps no assistant up wakes the server. */
    suspend fun present(ask: suspend () -> PresentResult?): String? {
        val answer = ask() ?: return NOT_PRESENTED
        if (answer.noResting) hub.ensureStarted()
        return answer.refusal
    }
}

/**
 * The assistant on screen as the server's code asks for it: the presenting client of the running server
 * ([AppServer]), or nothing before it has one. The callbacks come on the main thread. Plugins reach it as
 * [PluginContext.assistant].
 */
internal object Presenter {
    @Volatile internal var link: PresentationLink? = null

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether a report should wait for the person to finish reading. */
    fun reading(): Boolean = System.currentTimeMillis() < (link?.state?.readingUntilEpochMillis ?: 0L)

    /** Whether a full-screen front end is in front, which takes a wake itself. */
    fun inFront(): Boolean = link?.state?.inFront == true

    /** Whether the assistant is up and takes wakes. */
    fun ready(): Boolean = link?.state?.ready == true

    /** Brings the assistant up (card closed) when it should rest on this screen; [done] gets why not, null when up. */
    fun ensureRunning(done: (String?) -> Unit = {}) {
        main.launch { done(present(PresentationRequests.ENSURE_RUNNING, Empty)) }
    }

    /** Starts the assistant, with its card [open]; [done] gets why not, null when started. */
    fun start(open: Boolean, done: (String?) -> Unit = {}) {
        main.launch { done(present(PresentationRequests.START, PresentStartParams(open))) }
    }

    /** Opens or folds the card: null when done (or held for later), else why it could not be. */
    suspend fun card(command: OverlayCommand): String? = present(PresentationRequests.CARD, PresentCardParams(command))

    /**
     * Hands a wake to the assistant on screen, with how it came ([origin], in the host's own words); [taken] is false
     * when none is up to take it.
     */
    fun wake(origin: String, taken: (Boolean) -> Unit) {
        main.launch { taken(link?.ask(PresentationRequests.WAKE, PresentWakeParams(origin))?.taken == true) }
    }

    /** A request to the assistant on screen: null when none is up or none answers. */
    internal suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R? = link?.ask(method, params)

    /** A notification to the assistant on screen; nothing when none is up. */
    internal fun <P> tell(method: NotificationMethod<P>, params: P) {
        link?.tell(method, params)
    }

    private suspend fun <P> present(method: RequestMethod<P, PresentResult>, params: P): String? =
        link?.present { link?.ask(method, params) } ?: NOT_PRESENTED
}

private const val NOT_PRESENTED = "no assistant on screen"
