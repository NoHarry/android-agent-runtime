package cc.noharry.agent.model

/** Provider web_search capability declaration. */
enum class ServerSideSearchCapability(val wireName: String) {
    ANTHROPIC_SERVER_TOOL("anthropic-server-tool"),
    OPENAI_CHAT_OPTIONS("openai-chat-options"),
    OPENAI_RESPONSES_TOOL("openai-responses-tool"),
    NONE("none"),
    ;

    companion object {
        fun fromWireName(value: String?): ServerSideSearchCapability =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
}
