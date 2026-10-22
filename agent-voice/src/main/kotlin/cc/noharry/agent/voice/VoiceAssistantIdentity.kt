package cc.noharry.agent.voice

/**
 * Who the user talks to, for judges that read the words: the assistant's [name] (empty when it has none), how the name
 * is said in another script ([spokenAs], such as a romanization), and its [wakePhrase]. Candidate API, not frozen.
 */
data class VoiceAssistantIdentity(
    val name: String = "",
    val spokenAs: String = "",
    val wakePhrase: String = "",
) {
    companion object {
        /** An assistant with no name and no wake phrase; the judge's instructions say "the assistant". */
        val UNNAMED: VoiceAssistantIdentity = VoiceAssistantIdentity()
    }
}
