package cc.noharry.agent.spi

import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Error codes reach hosts and stored records as strings; the API dump pins the names, and this pins the strings. */
class RuntimeErrorCodeWireTest {
    @Test
    fun everyWireCodeIsItsName() {
        RuntimeErrorCode.entries.forEach { assertEquals(it.name, it.wireCode) }
    }
}
