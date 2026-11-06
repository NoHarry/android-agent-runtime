package cc.noharry.agent.kernel.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class StandardTraceRedactorTest {
    private val text = "order 13800138000 token=sk-abcdefgh123 lat=31.2304 on 2026-10-18"

    @Test
    fun eachKindCanBeKeptButSecrets() {
        assertEquals(
            "order [REDACTED_PHONE] [REDACTED_SECRET] [REDACTED_LOCATION] on 2026-10-18",
            StandardTraceRedactor().redact(text),
        )
        assertEquals(DefaultTraceRedactor.redact(text), StandardTraceRedactor().redact(text))
        assertEquals("order 13800138000 [REDACTED_SECRET] [REDACTED_LOCATION] on 2026-10-18", StandardTraceRedactor(phones = false).redact(text))
        assertEquals("order [REDACTED_PHONE] [REDACTED_SECRET] lat=31.2304 on 2026-10-18", StandardTraceRedactor(locations = false).redact(text))
        assertEquals(
            "order 13800138000 [REDACTED_SECRET] lat=31.2304 on 2026-10-18",
            StandardTraceRedactor(phones = false, locations = false).redact(text),
        )
    }
}
