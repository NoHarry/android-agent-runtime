package cc.noharry.agent.a2a.server

/** Candidate host output contract. Status fields must contain brief, credential-free summaries. */
sealed interface A2aOutput {
    /** Nonterminal work progress; tool arguments and raw results must stay in the host EventLog. */
    data class Status(
        val text: String,
        val phase: String,
        val toolName: String? = null,
        val toolCallId: String? = null,
    ) : A2aOutput

    /** Speakable text only: artifact metadata channel=speech, kind=tts, phase=progress/final. No local audio. */
    data class Speech(val text: String, val final: Boolean = false) : A2aOutput

    /** Nonterminal question projection. Reply with the same taskId/contextId and metadata.questionId. */
    data class Question(val questionId: String, val text: String, val options: List<String>, val state: String) : A2aOutput

    /** Display-only final text (channel=display, kind=response). Completed requires confirmed business success. */
    data class Result(val text: String, val state: State = State.COMPLETED) : A2aOutput

    /** Response states. Input-required ends this stream; reply as a new message in the same context. */
    enum class State(internal val wire: String) {
        COMPLETED("completed"), FAILED("failed"), CANCELED("canceled"), REJECTED("rejected"),
        INPUT_REQUIRED("input-required"),
    }
}

/** One artifact has exactly one consumer channel; legacy kind remains for existing clients. */
internal enum class A2aArtifactChannel(val wire: String, val legacyKind: String) {
    SPEECH("speech", "tts"), DISPLAY("display", "response"), QUESTION("question", "question"),
}
