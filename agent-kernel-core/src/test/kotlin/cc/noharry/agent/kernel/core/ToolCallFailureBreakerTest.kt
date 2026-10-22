package cc.noharry.agent.kernel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallFailureBreakerTest {
    @Test
    fun providerFailureCannotBePromotedByAnApparentlySuccessfulBusinessEnvelope() {
        val raw = """{"result":{"isError":false,"structuredContent":{"status":{"name":"OK"}}}}"""
        val result = cc.noharry.agent.kernel.api.ToolCallResult(false, raw).toBackendToolResult()
        assertEquals(false, backendToolCallResult(result).ok)
        assertEquals("BACKEND_CALL_FAILED", backendToolCallResult(result).code)
    }

    @Test
    fun typedBusinessRefusalsStayLosslessAndDoNotDisableRecoveryQueries() {
        val breaker = ToolCallFailureBreaker()
        val raw = """{"jsonrpc":"2.0","error":null,"result":{"isError":true,"structuredContent":{"status":{"name":"INVALID_ARGUMENT","details":{"reason":"too_many_items"}},"upstreamCode":10001,"data":{}}}}"""
        val delivered = cc.noharry.agent.kernel.api.ToolCallResult(false, raw).toBackendToolResult()
        val parsed = backendToolCallResult(delivered)
        assertEquals(raw, delivered)
        assertEquals(false, parsed.ok)
        assertEquals("INVALID_ARGUMENT", parsed.code)
        assertEquals(false, parsed.retryable)
        repeat(6) { breaker.record("run-1", "route_via_operate", """{"action":"add","index":$it}""", parsed) }
        assertNull(breaker.trippedFailure("run-1", "route_via_operate", """{"action":"query_limit"}"""))
    }

    @Test
    fun businessResponseResetsTransportFailuresWithoutBecomingSuccess() {
        val breaker = ToolCallFailureBreaker()
        val business = backendToolCallResult(
            """{"isError":true,"structuredContent":{"status":{"name":"CONFLICT"},"data":{}}}""",
        )
        breaker.record("run-1", "route", ARGS, failed)
        breaker.record("run-1", "route", ARGS, business)
        breaker.record("run-1", "route", ARGS, failed)
        assertEquals(false, business.ok)
        assertNull(breaker.trippedFailure("run-1", "route", ARGS))
    }

    @Test
    fun jsonRpcErrorCannotMasqueradeAsDeliveredBusinessResult() {
        val raw = """{"error":{"code":-32603},"result":{"structuredContent":{"status":{"name":"OK"}}}}"""
        assertEquals(false, isMcpBusinessResult(raw))
        assertEquals(false, backendToolCallResult(raw).ok)
        assertEquals(true, cc.noharry.agent.kernel.api.ToolCallResult(false, raw).toBackendToolResult().contains("BACKEND_CALL_FAILED"))
    }

    @Test
    fun providerFailureWithMissingMcpErrorFlagStaysFailure() {
        val raw = """{"structuredContent":{"status":{"name":"DEPENDENCY_UNAVAILABLE"}}}"""
        val result = cc.noharry.agent.kernel.api.ToolCallResult(false, raw).toBackendToolResult()
        assertEquals(false, backendToolCallResult(result).ok)
        assertEquals(true, result.contains("DEPENDENCY_UNAVAILABLE"))
    }

    @Test
    fun customProviderStatusNameDoesNotOverrideSuccessfulMcpResult() {
        val raw = """{"result":{"isError":false,"structuredContent":{"status":{"name":"READY"}}}}"""
        assertEquals(true, backendToolCallResult(raw).ok)
        assertNull(backendToolCallResult(raw).code)
    }

    private val failed =
        toolUseResult("""{"ok":false,"code":"BACKEND_CALL_FAILED","message":"device offline","retryable":true}""")
    private val succeeded = toolUseResult("""{"ok":true}""")

    @Test
    fun tripsAfterConsecutiveIdenticalFailures() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 2)

        assertNull(breaker.trippedFailure("run-1", "set_temperature", ARGS))
        breaker.record("run-1", "set_temperature", ARGS, failed)
        assertNull(breaker.trippedFailure("run-1", "set_temperature", ARGS))
        breaker.record("run-1", "set_temperature", ARGS, failed)

        val tripped = breaker.trippedFailure("run-1", "set_temperature", ARGS)
        assertEquals(2, tripped?.failureCount)
        assertEquals("BACKEND_CALL_FAILED", tripped?.lastFailureCode)
    }

    @Test
    fun successResetsConsecutiveFailureCount() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 2)

        breaker.record("run-1", "set_temperature", ARGS, failed)
        breaker.record("run-1", "set_temperature", ARGS, succeeded)
        breaker.record("run-1", "set_temperature", ARGS, failed)

        assertNull(breaker.trippedFailure("run-1", "set_temperature", ARGS))
    }

    @Test
    fun differentArgumentsAreTrackedIndependently() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 2)

        breaker.record("run-1", "set_temperature", ARGS, failed)
        breaker.record("run-1", "set_temperature", ARGS, failed)
        breaker.record("run-1", "set_temperature", OTHER_ARGS, failed)

        assertEquals(2, breaker.trippedFailure("run-1", "set_temperature", ARGS)?.failureCount)
        assertNull(breaker.trippedFailure("run-1", "set_temperature", OTHER_ARGS))
    }

    @Test
    fun differentRunsAreTrackedIndependently() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 1)

        breaker.record("run-1", "set_temperature", ARGS, failed)

        assertEquals(1, breaker.trippedFailure("run-1", "set_temperature", ARGS)?.failureCount)
        assertNull(breaker.trippedFailure("run-2", "set_temperature", ARGS))
    }

    @Test
    fun toolTierTripsAcrossVaryingArgumentsAndSuccessResets() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 2, maxConsecutiveToolFailures = 4)

        for (index in 1..4) {
            breaker.record("run-1", "set_temperature", """{"value":$index}""", failed)
        }

        val tripped = breaker.trippedFailure("run-1", "set_temperature", """{"value":99}""")
        assertEquals(TrippedToolCallScope.TOOL, tripped?.scope)
        assertEquals(4, tripped?.failureCount)

        breaker.record("run-1", "set_temperature", """{"value":5}""", succeeded)
        assertNull(breaker.trippedFailure("run-1", "set_temperature", """{"value":99}"""))
    }

    @Test
    fun mcpIsErrorEnvelopeCountsAsFailure() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 2)
        val envelope = backendToolCallResult(
            """{"result":{"content":[{"type":"text","text":"oops"}],"isError":true,"statusCode":0},"id":1,"jsonrpc":"2.0"}""",
        )

        assertEquals(false, envelope.ok)
        assertEquals(MCP_TOOL_IS_ERROR_CODE, envelope.code)

        breaker.record("run-1", "set_temperature", ARGS, envelope)
        breaker.record("run-1", "set_temperature", ARGS, envelope)
        assertEquals(
            TrippedToolCallScope.IDENTICAL_ARGUMENTS,
            breaker.trippedFailure("run-1", "set_temperature", ARGS)?.scope,
        )
    }

    @Test
    fun topLevelMcpIsErrorWithResultObjectCountsAsFailure() {
        val parsed = backendToolCallResult(
            """{"result":{"content":[],"isError":false},"isError":true,"id":1,"jsonrpc":"2.0"}""",
        )

        assertEquals(false, parsed.ok)
        assertEquals(MCP_TOOL_IS_ERROR_CODE, parsed.code)
    }

    @Test
    fun successfulMcpEnvelopeStaysOk() {
        val parsed = backendToolCallResult(
            """{"result":{"content":[{"type":"text","text":"done"}],"isError":false,"statusCode":10000},"id":2,"jsonrpc":"2.0"}""",
        )

        assertEquals(true, parsed.ok)
    }

    @Test
    fun malformedBackendPayloadFailsClosed() {
        val parsed = backendToolCallResult("not-json")

        assertEquals(false, parsed.ok)
        assertEquals(MCP_RESULT_INVALID_JSON_CODE, parsed.code)
    }

    @Test
    fun oldRunsAreEvicted() {
        val breaker = ToolCallFailureBreaker(maxConsecutiveFailures = 1)
        breaker.record("run-0", "set_temperature", ARGS, failed)

        for (index in 1..EVICTING_RUN_COUNT) {
            breaker.record("run-$index", "set_temperature", ARGS, failed)
        }

        assertNull(breaker.trippedFailure("run-0", "set_temperature", ARGS))
        assertEquals(
            1,
            breaker.trippedFailure("run-$EVICTING_RUN_COUNT", "set_temperature", ARGS)?.failureCount,
        )
    }

    private companion object {
        const val ARGS = """{"degreesCelsius":22}"""
        const val OTHER_ARGS = """{"degreesCelsius":18}"""
        const val EVICTING_RUN_COUNT = 8
    }
}
