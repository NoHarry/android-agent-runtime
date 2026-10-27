package cc.noharry.agent.kernel.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model-visible tool names fit what model APIs accept: ASCII letters, digits, `_` and `-`, at most 64 characters. */
class ToolNameTest {
    private val allowed = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test fun asciiNamesAreUnchanged() {
        assertEquals("mcp__car__get_climate", mcpQualifiedName("car", "get_climate"))
        assertEquals("mcp__device__media_status", mcpQualifiedName("device", "media.status"))
    }

    @Test fun aNameInChineseBecomesAStableHash() {
        val name = mcpQualifiedName("车机", "查空调")
        assertTrue(name, allowed.matches(name))
        assertEquals(name, mcpQualifiedName("车机", "查空调"))
        assertNotEquals(name, mcpQualifiedName("车机", "开空调"))
    }

    @Test fun aLongNameIsCutToSixtyFourAndStaysDistinct() {
        val server = "home_assistant_living_room_bridge"
        val first = mcpQualifiedName(server, "set_climate_control_target_temperature_for_zone")
        val second = mcpQualifiedName(server, "set_climate_control_target_temperature_for_zone_all")
        assertTrue(first, allowed.matches(first))
        assertTrue(second, allowed.matches(second))
        assertNotEquals(first, second)
    }
}
