package cc.noharry.agent.appserver.tools

import cc.noharry.agent.appserver.AppServerPluginApi

/** What a person's words hold that is never kept, in a memory or as a plan's 原话 ([sensitiveIn]). */
@AppServerPluginApi
enum class Sensitive {
    /** A password, a code and the like. */
    SECRET,

    /** An ID, card or other long number. */
    LONG_NUMBER,
}

/** What [text] holds that is never kept, or null when it holds nothing such. */
@AppServerPluginApi
fun sensitiveIn(text: String): Sensitive? = when {
    SECRET_WORDS.any { it in text } || SECRET_WORDS_EN.containsMatchIn(text) -> Sensitive.SECRET
    LONG_NUMBER.containsMatchIn(text) -> Sensitive.LONG_NUMBER
    else -> null
}

private val SECRET_WORDS = listOf("密码", "验证码", "口令", "支付码", "PIN码", "pin码", "CVV", "cvv")

/**
 * The same in English, with no letter either side (Chinese may stand right next to one: 「银行OTP是908172」): a PIN in
 * capitals or followed by what it is (「我的pin是1234」, "pin 4321", "my pin is 4321"), so "enamel pins" and "Pin the card" are kept.
 */
private val SECRET_WORDS_EN = Regex(
    """(?<![A-Za-z])(?:(?i:passwords?|passcodes?|passphrases?|pin ?(?:codes?|numbers?)|verification codes?|security codes?|""" +
        """one[- ]time (?:codes?|passwords?)|otp|cvc|cvv)|PINs?|(?i:pin)(?=\s*(?:is\s|是|码|号|[:：=]|\d)))(?![A-Za-z])""",
)

/** 15 digits or more, however they are grouped: 「6222 0202 …」「6222-0202-…」「6222/0202/…」. */
private val LONG_NUMBER = Regex("""\d(?:[\s\-/.·_]{0,3}\d){14,}""")
