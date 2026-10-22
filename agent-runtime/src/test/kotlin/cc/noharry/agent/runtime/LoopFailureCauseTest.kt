package cc.noharry.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopFailureCauseTest {
    @Test
    fun theRootCauseIsNamedOnOneShortLine() {
        val error = IllegalStateException("wrapper", IllegalArgumentException("tool schema   broken\n at field x"))
        assertEquals("IllegalArgumentException: tool schema broken at field x", loopFailureCause(error))
        assertEquals("NullPointerException", loopFailureCause(NullPointerException()))
        assertTrue(loopFailureCause(RuntimeException("x".repeat(500))).length <= 160)
    }
}
