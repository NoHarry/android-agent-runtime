package cc.noharry.agent.appserver.session

import cc.noharry.agent.appserver.protocol.ConversationItem


/**
 * The transcripts the server keeps live: the selected session's ([focus], the one the screens and voice show) and those
 * of other sessions that stay loaded, a run going on in them or a client following them ([keepLoaded]). A run's items
 * go to its own session's transcript wherever that is shown; selecting another session trades the two, so nothing in
 * flight is lost. Main thread.
 */
internal class LoadedTranscripts(
    val focus: TranscriptStore,
) {
    private val background = mutableMapOf<String, TranscriptStore>()

    /** Whether a session not selected stays loaded: a run going on in it, a client following it. */
    @Volatile internal var keepLoaded: (String) -> Boolean = { false }

    /** [sessionId]'s live transcript, or null when it is neither selected ([activeSessionId]) nor loaded. */
    fun of(sessionId: String, activeSessionId: String): TranscriptStore? =
        if (sessionId == activeSessionId) focus else background[sessionId]

    /** Loads [sessionId] beside the selected one, from [stored] when it is not loaded yet. */
    fun load(sessionId: String, stored: () -> List<ConversationItem>): TranscriptStore =
        background.getOrPut(sessionId) { TranscriptStore().apply { replaceAll(stored()) } }

    /** Lets [sessionId] go unless it should stay loaded. */
    fun release(sessionId: String) {
        if (!keepLoaded(sessionId)) background.remove(sessionId)
    }

    fun forget(sessionId: String) {
        background.remove(sessionId)
    }

    fun clear() = background.clear()

    /**
     * [next] becomes the selected session after [previous]: the focus takes [next]'s live transcript, or [stored] when
     * it has none, and [previous] keeps its own when it should stay loaded ([keepPrevious]).
     */
    internal fun select(previous: String, next: String, keepPrevious: Boolean, stored: () -> List<ConversationItem>) {
        if (previous == next) {
            if (!keepPrevious) focus.replaceAll(stored())
            return
        }
        val incoming = background.remove(next)
        if (incoming != null) {
            focus.swapContents(incoming)
            if (keepPrevious) background[previous] = incoming
            return
        }
        if (keepPrevious) background[previous] = TranscriptStore().also { it.swapContents(focus) }
        focus.replaceAll(stored())
    }

    /** Every loaded session's items but the selected one's. */
    internal fun snapshots(): Map<String, List<ConversationItem>> = background.mapValues { it.value.snapshot() }

    /** The loaded session where an approval waits under [pendingId], the selected one ([activeSessionId]) first. */
    internal fun sessionWaitingFor(pendingId: String, activeSessionId: String): String? =
        activeSessionId.takeIf { focus.waitsFor(pendingId) } ?: background.entries.firstOrNull { it.value.waitsFor(pendingId) }?.key
}
