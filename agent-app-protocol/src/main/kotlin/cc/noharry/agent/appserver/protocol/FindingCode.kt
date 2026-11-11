package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.Serializable

/**
 * What a [Finding] is. Each code but the first five is worded by the app's string of the same name in lower case,
 * taking the finding's values in order; the first five only arrange the findings they hold.
 */
@Serializable
enum class FindingCode : FindingKind {
    /** One value shown as it is. */
    TEXT,

    /** Findings side by side. */
    PARTS,

    /** Findings as a list. */
    LIST,

    /** Findings that each failed, one after another. */
    ALTERNATIVES,

    /** Findings one per line. */
    LINES,

    // Plans: what came of one coming due (最近触发) and of its controls
    PLANS_NOTE_HANDED,

    PLANS_NOTE_LATE,

    PLANS_NOTE_REMINDED,

    PLANS_NOTE_PAUSED_DUE,

    PLANS_NOTE_MISSED,

    PLANS_NOTE_RUNAWAY,

    PLANS_ACTION_DONE,

    PLANS_ACTION_FAILED,

    PLANS_ACTION_DENIED,

    PLANS_PROBLEM_OFFLINE,

    /** A plan made on the plans page was not kept: too long, nothing to say or do, or the book is full. */
    PLANS_PROBLEM_NOT_SAVED,

    PLANS_PROBLEM_NO_ANSWER,

    PLANS_PROBLEM_NO_RESULT,

    PLANS_PROBLEM_ERROR,

    PLANS_PROBLEM_REFUSED,

    // A2A: why a task handed to an agent ended with no report of the agent's own
    /** The person stopped following it. */
    A2A_STOPPED,

    /** Not followed again after the app restarted: the agent's settings (its name) are gone. */
    A2A_LOST_SETTINGS,

    /** No result after so many minutes, so following stopped. */
    A2A_TIMED_OUT,

    /** Its progress could not be read so many times in a row (the last reason). */
    A2A_UNREADABLE,

    // Requests
    HTTP_STATUS,

    // The image model's settings
    IMAGE_PROBLEM_BASE_URL_PARTS,

    IMAGE_PROBLEM_BASE_URL_SCHEME,

    IMAGE_PROBLEM_KEY_NOT_REMOVED,

    IMAGE_PROBLEM_KEY_NOT_SAVED,

    IMAGE_PROBLEM_KEY_WHITESPACE,

    IMAGE_PROBLEM_MODEL_WHITESPACE,

    IMAGE_PROBLEM_NOT_SAVED,

    IMAGE_PROBLEM_NO_KEY,

    IMAGE_PROBLEM_NO_MODEL,

    IMAGE_PROBLEM_SIZE,

    IMAGE_PROBLEM_TIMEOUT,

    // MCP servers' tools
    MCP_SERVER_TOOLS_TIMEOUT,

    MCP_SERVER_UNREACHABLE,

    MCP_TOOLS_JSON_MISSING,

    MCP_UNKNOWN_PROVIDER,

    // Memory writes
    MEMORY_CATEGORY_HOME,

    MEMORY_CATEGORY_IDENTITY,

    MEMORY_CATEGORY_MEDIA,

    MEMORY_CATEGORY_OTHER,

    MEMORY_CATEGORY_PEOPLE,

    MEMORY_CATEGORY_PLACES,

    MEMORY_CATEGORY_TRAVEL,

    MEMORY_REFUSED_CATEGORY_FULL,

    MEMORY_REFUSED_EMPTY,

    MEMORY_REFUSED_FULL,

    MEMORY_REFUSED_LONG_NUMBER,

    MEMORY_REFUSED_MISSING,

    MEMORY_REFUSED_NOT_SAVED,

    MEMORY_REFUSED_SECRET,

    MEMORY_REFUSED_TOO_LONG,

    // Chat models and DeepSeek
    MODEL_BALANCE,

    MODEL_BALANCE_FAILED,

    MODEL_BALANCE_KEY_REJECTED,

    MODEL_CHOOSE_MODEL_FIRST,

    MODEL_DEEPSEEK_AVAILABLE,

    MODEL_HTTP_ERROR,

    MODEL_MISSING_API_KEY,

    MODEL_PROBE_DROPPED,

    MODEL_PROBE_NOT_BALANCE,

    MODEL_PROBE_NOT_HTTP,

    MODEL_PROBE_NOT_SEARCH,

    MODEL_PROBE_NO_MODEL,

    MODEL_PROBE_PASSED_THROUGH,

    MODEL_PROBE_UNREACHABLE,

    // Custom skills
    SKILLS_CHECK_DESCRIPTION_BRACKETS,

    SKILLS_CHECK_DESCRIPTION_LINE,

    SKILLS_CHECK_DESCRIPTION_LONG,

    SKILLS_CHECK_DESCRIPTION_SHORT,

    SKILLS_CHECK_DUPLICATE,

    SKILLS_CHECK_EMPTY,

    SKILLS_CHECK_EMPTY_BODY,

    SKILLS_CHECK_FIRST_LINE,

    SKILLS_CHECK_INDENT,

    SKILLS_CHECK_LONG_BODY,

    SKILLS_CHECK_METADATA,

    SKILLS_CHECK_NAME_EMPTY,

    SKILLS_CHECK_NAME_FORMAT,

    SKILLS_CHECK_NAME_TAKEN,

    SKILLS_CHECK_NOT_FIELD,

    SKILLS_CHECK_NO_DESCRIPTION,

    SKILLS_CHECK_NO_NAME,

    SKILLS_CHECK_SCENES,

    SKILLS_CHECK_SECTIONS,

    SKILLS_CHECK_SINGLE_QUOTES,

    SKILLS_CHECK_TOO_BIG,

    SKILLS_CHECK_UNCLOSED,

    SKILLS_CHECK_UNKNOWN_FIELD,

    SKILLS_CUSTOM_SAVE_FAILED,

    TOOLS_PROMPT_STATS,

    TOOLS_PROMPT_STATS_SHORTENED,
}
