package cc.noharry.agent.kernel.core.tools
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentSchemaValidatorTest {
    @Test fun nestedArrayLimitsApplyBeforeItemValidationAndAtBoundary() {
        val schema = """{"type":"object","properties":{"route":{"type":"object","properties":{
            "stops":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object",
            "required":["point"],"properties":{"point":{"type":"array","minItems":2,"maxItems":2}}}}
        }}}}"""
        assertValid("""{"route":{"stops":[{"point":[1,2]},{"point":[3,4]},{"point":[5,6]}]}}""", schema)
        assertInvalid("""{"route":{"stops":[]}}""", schema, "route.stops", "minItems=1")
        assertInvalid("""{"route":{"stops":[{},{},{},{}]}}""", schema, "route.stops", "maxItems=3")
        assertInvalid("""{"route":{"stops":[{"point":[1,2,3]}]}}""", schema, "route.stops[0].point", "maxItems=2")
    }

    @Test fun limitsDoNotRequireItemSchemaAndZeroIsEnforced() {
        val schema = """{"properties":{"values":{"type":"array","maxItems":0}}}"""
        assertValid("""{"values":[]}""", schema)
        assertInvalid("""{"values":[1]}""", schema, "values", "maxItems=0")
    }

    @Test fun absentBoundsAndNullUnionRemainCompatible() {
        val schema = """{"properties":{"values":{"type":["array","null"],"maxItems":1}}}"""
        assertValid("""{"values":null}""", schema)
        assertValid("""{"values":[1]}""", schema)
        assertValid("""{"values":[1,2,3,4]}""", """{"properties":{"values":{"type":"array"}}}""")
    }

    @Test fun malformedLimitIsNotSilentlyIgnored() {
        listOf("-1", "1.5", "\"3\"", "null").forEach { limit ->
            val schema = """{"properties":{"values":{"type":"array","maxItems":$limit}}}"""
            val result = validateBackendToolArguments("tool", """{"values":[]}""", schema)
            assertTrue(result is ToolArgumentValidationResult.Invalid)
            assertTrue((result as ToolArgumentValidationResult.Invalid).resultJson().contains("INVALID_TOOL_SCHEMA"))
        }
    }

    private fun assertValid(arguments: String, schema: String) {
        assertTrue(validateBackendToolArguments("tool", arguments, schema) is ToolArgumentValidationResult.Valid)
    }

    private fun assertInvalid(arguments: String, schema: String, path: String, limit: String) {
        val result = validateBackendToolArguments("tool", arguments, schema) as ToolArgumentValidationResult.Invalid
        assertTrue(result.resultJson().contains("SCHEMA_VALIDATION_FAILED"))
        assertTrue(result.resultJson().contains(path))
        assertTrue(result.resultJson().contains(limit))
    }
}
