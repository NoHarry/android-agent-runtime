package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.mcp.ChineseMcpMessages
import cc.noharry.agent.mcp.McpClientMessages
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What the server's own code tells the model, in the language the host's assistant answers in ([Persona.language]):
 * Chinese for a Chinese persona, else English that names the persona's language, or leaves the reply in the user's own
 * when the persona sets none.
 */
internal class ModelWords private constructor(
    /** The report after an accepted offer, as the core rules ask for it: "a concise Chinese report of its result". */
    val briefReport: String,
    /** What finish's response is, as the core rules say it: "concise Chinese", "concise, in the user's language". */
    val conciseResponse: String,
    /** Bare replies that answer the question just asked, as the core rules give them. */
    val shortReplies: String,
    /** The run context's closing line, on the language to think in; empty when the persona sets none. */
    val thinkLine: String,
    /** The tool servers' problems as a run's tool results tell the model. */
    val mcp: McpClientMessages,
    private val nowLabel: String,
    private val weekdays: List<String>,
    private val foreground: (label: String, packageName: String) -> String,
    private val lateAnswer: (question: String, answer: String) -> String,
) {
    /** 「现在：2026-10-19 周一 11:35」, "Now: 2026-10-19 Mon 11:35": ASCII digits whatever the device's locale. */
    fun clock(time: ZonedDateTime): String =
        "%s%s %s %02d:%02d".format(Locale.ROOT, nowLabel, time.toLocalDate(), weekdays[time.dayOfWeek.value - 1], time.hour, time.minute)

    /** The app in front, named so it is not opened again, and what an unaddressed "pause" most likely means. */
    fun foregroundApp(label: String, packageName: String): String = foreground(label, packageName)

    /** A reply to a question whose run had ended: it carries the question, as the new run starts from this text. */
    fun lateAnswer(question: String, answer: String): String = lateAnswer.invoke(question, answer)

    /** Whether the server's lines are the Chinese ones. */
    val isChinese: Boolean get() = this === CHINESE

    companion object {
        val CHINESE = ModelWords(
            briefReport = "a concise Chinese report of its result",
            conciseResponse = "concise Chinese",
            shortReplies = "可以、不要、继续、刚才那个",
            thinkLine = THINK_IN_CHINESE,
            mcp = ChineseMcpMessages,
            nowLabel = "现在：",
            weekdays = CHINESE_WEEKDAYS,
            foreground = { label, packageName ->
                "屏幕前台：「$label」（$packageName），已经开着，操作它不用再打开；用户没说对象的「暂停」「继续」「下一个」「这个」，" +
                    "先想到它里面正在放、正在看的内容。"
            },
            lateAnswer = { question, answer -> "关于你之前的问题「$question」，我的回答是：$answer" },
        )

        /** In English, naming [language] (a BCP 47 tag) as what replies are written in; the user's own without one. */
        fun english(language: String? = null): ModelWords {
            val name = language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }?.takeIf { it.isNotBlank() }
            val language = name ?: "the user's language"
            return ModelWords(
                briefReport = "a concise report of its result in $language",
                conciseResponse = "concise, in $language",
                shortReplies = "yes, no, go on, that one",
                thinkLine = name?.let { "Think through this turn in $it." }.orEmpty(),
                mcp = McpClientMessages.Default,
                nowLabel = "Now: ",
                weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
                foreground = { label, packageName ->
                    "In front on the screen: \"$label\" ($packageName), already open, so acting on it needs no opening; an " +
                        "unaddressed \"pause\", \"go on\", \"next\" or \"this\" most likely means what plays or shows in it."
                },
                lateAnswer = { question, answer -> "About your earlier question \"$question\", my answer is: $answer" },
            )
        }

        /** The words for a persona answering in [language]: Chinese for any Chinese tag ("zh", "zh-CN", "zh_CN"), else English. */
        fun forLanguage(language: String?): ModelWords {
            val tag = language?.replace('_', '-')
            return if (tag != null && Locale.forLanguageTag(tag).language == "zh") CHINESE else english(tag)
        }
    }
}

/**
 * A screen may show the thinking as it comes, for the user to read; a model may think in English for an English system
 * prompt and keep to it even when told there. This line, last in the run's own context, comes right before it starts
 * thinking; at the start of that context it is followed only now and then.
 */
internal const val THINK_IN_CHINESE = "本轮的思考过程（thinking）全程用简体中文写，不要用英文。"

/**
 * Cue words of a tool that offers itself on the first round, in either language whatever the persona's: [chinese] anywhere
 * in the text, [english] as whole words and only in text without Chinese, so 「播放 Call Me Maybe」 is no "call me".
 */
@cc.noharry.agent.appserver.AppServerPluginApi
class Cues(private val chinese: List<String>, english: List<String>) {
    private val englishWords = Regex("\\b(?:" + english.joinToString("|", transform = Regex::escape) + ")\\b", RegexOption.IGNORE_CASE)

    fun heardIn(text: String): Boolean = chinese.any { it in text } ||
        (text.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } && englishWords.containsMatchIn(text.replace('’', '\'')))
}

/**
 * [chinese] for a Chinese persona, else [english]: a line of the server's own that the model reads, or anything else
 * that follows the persona's language (a plugin's words, a cache key).
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun <T> modelText(chinese: T, english: T): T = if (AppHost.words.isChinese) chinese else english

/** A late answer to [question], in the persona's words, as a new message ("关于你之前的问题…"). */
@cc.noharry.agent.appserver.AppServerPluginApi
fun lateAnswerText(question: String, answer: String): String = AppHost.words.lateAnswer(question, answer)

private val CHINESE_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
