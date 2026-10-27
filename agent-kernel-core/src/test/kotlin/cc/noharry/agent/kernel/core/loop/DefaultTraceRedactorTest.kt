package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultTraceRedactorTest {
    @Test
    fun phoneNumbersAreHiddenButDatesStay() {
        listOf("13800138000", "138 0013 8000", "+86 138 0013 8000", "020-87654321", "400-820-8820", "20261009-172728", "06 12 34 56 78")
            .forEach { assertEquals(it, "call [REDACTED_PHONE] now", DefaultTraceRedactor.redact("call $it now")) }

        listOf("2026-10-18", "10-18 10-19", "2026-10-17 2026-10-18", "2026-9-8 周日、10-19 周一")
            .forEach { assertEquals(it, "时间窗：$it", DefaultTraceRedactor.redact("时间窗：$it")) }
    }
}
