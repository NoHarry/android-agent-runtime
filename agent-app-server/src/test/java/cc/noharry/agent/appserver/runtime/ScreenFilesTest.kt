package cc.noharry.agent.appserver.runtime

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFilesTest {
    @Test
    fun namesSurviveTracePhoneRedaction() {
        // Mirrors TraceSecurity's phone pattern: a dated name was rewritten to [REDACTED_PHONE] on the bench.
        val phone = Regex("""(?<!\d)(?:\+?\d[\d -]{8,}\d)(?!\d)""")
        val random = Random(7)
        listOf(1_791_538_029_106L, 1_800_000_000_000L, 9_999_999_999_999L).forEach { now ->
            val name = screenFileName("img", now, random)
            assertFalse(name, phone.containsMatchIn(name))
            assertTrue(name, Regex("""img-[a-z0-9]+-[a-z]{4}""").matches(name))
        }
    }
}
