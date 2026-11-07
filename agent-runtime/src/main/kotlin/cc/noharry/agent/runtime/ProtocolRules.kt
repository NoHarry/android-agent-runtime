package cc.noharry.agent.runtime

/**
 * The lines of the runtime's protocol section, which tells the model how to use the runtime's own tools: a host rewords,
 * drops or adds lines ([defaults] are the runtime's, for the tools on offer; the first names the protocol; no lines
 * leave the section out). It is asked each time the prompt is assembled: keep it quick and pure, since the prompt is
 * cached as long as it stays the same. Its lines count toward the prompt's soft budget, so long ones shorten the list of
 * tool areas and skills that follows.
 */
fun interface ProtocolRules {
    fun rules(defaults: List<String>): List<String>

    companion object {
        /** The runtime's own lines. */
        val DEFAULT: ProtocolRules = ProtocolRules { it }
    }
}
