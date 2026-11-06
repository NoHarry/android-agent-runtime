package cc.noharry.agent.kernel.api

/**
 * Whether a final answer only says what the agent is about to do ("I'll now look that up") instead of answering. Such an
 * answer goes back to the model once for a real one, and fails the run if the next is no better. The runtime's own
 * check knows English and Chinese phrasings; a host whose users write another language, or whose answers are short
 * confirmations the check would mistake, supplies its own, also to the runtimes it builds for subagents.
 *
 * It is called on the run's thread, possibly several times for one answer and for runs at once: keep it quick, without
 * side effects and safe to call concurrently. One that throws fails the run.
 */
fun interface NarrationCheck {
    fun isNarration(answer: String): Boolean

    companion object {
        /** Takes no answer for an announcement (an answer with tool-call syntax in it, or nothing, is still sent back). */
        val NONE: NarrationCheck = NarrationCheck { false }
    }
}
