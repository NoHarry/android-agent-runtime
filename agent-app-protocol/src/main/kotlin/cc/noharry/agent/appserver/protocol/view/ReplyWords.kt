package cc.noharry.agent.appserver.protocol.view

/**
 * The words a tap or a confirmed panel sends as the user's reply, in the language the client shows: 「第2个」「音量设为30%」
 * or "Option 2", "Volume to 30%". The server reads either back ([controlPicks], an option picked by its ordinal), and
 * words a chosen value in its own language for the model ([valueText]).
 */
enum class ReplyWords {
    CHINESE,
    ENGLISH,
    ;

    internal fun pick(ordinal: Int): String = if (this == CHINESE) "第${ordinal}个" else "Option $ordinal"

    internal fun setTo(label: String, value: String): String = if (this == CHINESE) "${label}设为$value" else "$label to $value"

    internal fun state(on: Boolean): String = when (this) {
        CHINESE -> if (on) "打开" else "关闭"
        ENGLISH -> if (on) "on" else "off"
    }

    internal fun switched(label: String, on: Boolean): String = if (this == CHINESE) label + state(on) else "$label ${state(on)}"

    internal fun chose(label: String, names: List<String>): String =
        if (this == CHINESE) "${label}选${names.joinToString(list)}" else "$label: ${names.joinToString(list)}"

    /** A control's name and its chosen value: 「空调温度」24℃, "Temperature" 24℃. */
    internal fun value(label: String, value: String): String = if (this == CHINESE) "「$label」$value" else "\"$label\" $value"

    /** Between the parts of a confirmed panel. */
    internal val parts: String get() = if (this == CHINESE) "，" else ", "

    /** Between the names in a list. */
    val list: String get() = if (this == CHINESE) "、" else ", "

    companion object {
        /** Chinese for a Chinese [language] (a BCP 47 tag or a language code such as "zh"), else English. */
        fun forLanguage(language: String?): ReplyWords =
            if (language?.lowercase()?.substringBefore('-')?.substringBefore('_') == "zh") CHINESE else ENGLISH
    }
}
