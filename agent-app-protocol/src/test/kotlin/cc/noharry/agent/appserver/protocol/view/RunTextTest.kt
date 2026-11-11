package cc.noharry.agent.appserver.protocol.view

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** A run's duration as a screen shows it: ASCII digits whatever the device's locale. */
class RunTextTest {
    @Test
    fun aDurationIsWrittenWithTheSameDigitsInEveryLocale() {
        val before = Locale.getDefault()
        try {
            for (tag in listOf("en-US", "ar-EG-u-nu-arab", "hy-AM")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "2m 05s", formatWorkedDuration(125_000))
                assertEquals(tag, "1h 0m 5s", formatWorkedDuration(3_605_000))
                assertEquals(tag, "9s", formatWorkedDuration(9_400))
            }
        } finally {
            Locale.setDefault(before)
        }
    }
}
