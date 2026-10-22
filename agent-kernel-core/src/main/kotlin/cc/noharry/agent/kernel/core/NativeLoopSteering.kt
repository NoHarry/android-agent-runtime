package cc.noharry.agent.kernel.core

/** Tells the model why history shows a completed answer followed by new user input. */
internal const val WITHHELD_FINISH_NOTE =
    "New user input arrived while you were finishing; the previous finish response above was not delivered. " +
        "Incorporate the input below, then call finish again with the complete final response."

/** Canonical note when the continuation could not run and the withheld finish was delivered instead. */
internal const val WITHHELD_FINISH_FALLBACK = "Delivered the previously accepted finish response; continuation unavailable:"

/** Steering and question replies enter the prompt as user messages that carry their correlation. */
internal fun RuntimeSteeringInput.toModelMessageContent(): String =
    if (questionId == null) "User steering input (${requestId}):\n$text" else
        "Answer to question $questionId ($questionText):\n$text"

/** Tools whose results never advance task state, so a todo reminder is not stale after them. */
internal val TODO_NEUTRAL_TOOL_NAMES = setOf(
    "list_dir",
    "read_file",
    "grep",
    "search_tools",
    "update_todo",
    "finish",
    "ask_async",
)
