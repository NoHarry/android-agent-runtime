package cc.noharry.agent.exec.js

import org.junit.Assert.assertThrows
import org.junit.Test

class ZiplineExecEngineLimitsTest {
    @Test
    fun limitsMustMakeSense() {
        ZiplineExecEngine(maxToolCalls = 0, memoryLimitBytes = 8L shl 20, maxStackSizeBytes = 512L shl 10)
        ZiplineExecEngine(maxToolCalls = Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { ZiplineExecEngine(maxToolCalls = -1) }
        assertThrows(IllegalArgumentException::class.java) { ZiplineExecEngine(memoryLimitBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { ZiplineExecEngine(maxStackSizeBytes = -1) }
        // QuickJS runs on the calling thread, whose stack a larger limit would overrun.
        assertThrows(IllegalArgumentException::class.java) { ZiplineExecEngine(maxStackSizeBytes = 2L shl 20) }
    }
}
