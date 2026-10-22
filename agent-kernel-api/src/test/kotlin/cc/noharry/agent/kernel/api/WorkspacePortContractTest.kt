@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspacePortContractTest {
    @Test
    fun workspaceCapabilityIsSingleCanonicalKey() {
        val key = AgentCapabilities.WORKSPACE
        assertEquals("workspace", key.name)
        assertEquals(CapabilityCardinality.SINGLE, key.cardinality)
        assertSame(key, AgentCapabilities.WORKSPACE)
    }

    @Test
    fun workspaceCallContextDefaultsAreEmptyStrings() {
        val context = WorkspaceCallContext()
        assertEquals("", context.sessionId)
        assertEquals("", context.runId)
        assertEquals("", context.requestId)
        assertEquals(null, context.scene)
        assertEquals(null, context.modelRef)
    }

    @Test
    fun workspaceExecutionResultCarriesJsonAndTelemetry() {
        val telemetry = WorkspaceExecutionTelemetry(resolveDurationMs = 3, schemaCacheHit = true)
        val result = WorkspaceExecutionResult("""{"ok":true}""", telemetry)
        assertEquals("""{"ok":true}""", result.resultJson)
        assertEquals(3L, result.telemetry.resolveDurationMs)
        assertEquals(true, result.telemetry.schemaCacheHit)
    }
}
