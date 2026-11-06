package cc.noharry.agent.runtime

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AcceptLanguageTest {
    @Test
    fun pagesAreAskedForInTheDevicesLanguageThenEnglish() {
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", acceptLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals("de,en;q=0.8", acceptLanguage(Locale.GERMAN))
        assertEquals("en-US,en;q=0.9", acceptLanguage(Locale.US))
        assertEquals("en", acceptLanguage(Locale.ENGLISH))
        assertEquals("en", acceptLanguage(Locale.ROOT))
        // Android's tags carry a script and the user's regional preferences: a site reads the language and region.
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", acceptLanguage(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", acceptLanguage(Locale.forLanguageTag("zh-Hans-CN-u-fw-mon")))
        assertEquals("he-IL,he;q=0.9,en;q=0.8", acceptLanguage(Locale.forLanguageTag("he-IL")))
    }
}
