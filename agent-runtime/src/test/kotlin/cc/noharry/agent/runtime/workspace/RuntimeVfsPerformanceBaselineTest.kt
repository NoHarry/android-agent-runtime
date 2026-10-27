package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.runtime.VfsPerformanceBaselineFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeVfsPerformanceBaselineTest {
    @Test
    fun benchMaterializedToolsJsonRepeatedReadFixtureRecordsPreOptimizationCost() {
        assertEquals(10, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READS)
        assertEquals(10L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READ_OVERLAY_ATTACHES)
        assertEquals(10L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READ_MATERIALIZER_READS)
        assertEquals(10L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READ_BYTE_ENCODES)
    }

    @Test
    fun benchMaterializedToolsJsonGrepFixtureRecordsPreOptimizationCost() {
        assertEquals(1L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_GREP_OVERLAY_ATTACHES)
        assertEquals(1L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_GREP_MATERIALIZER_SEARCH_FILES)
        assertEquals(1L, VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_GREP_SEARCH_BYTE_ENCODES)
    }
}
