package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ToolCallContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionToolsTest {
    private val context = ToolCallContext("session", "run", "request")
    private val tools = functionTools("math", "Math") {
        tool("add", "Adds two numbers.", inputSchema = """{"type":"object"}""", readOnly = true) { args ->
            (args.getValue("a").jsonPrimitive.double + args.getValue("b").jsonPrimitive.double).toString()
        }
        tool("info", "Returns JSON.") { """{"answer":42}""" }
        tool("broken", "Always fails.") { error("disk full") }
    }

    @Test
    fun listsEachToolWithItsSchemaAndEffect() = runBlocking {
        val listed = tools.listTools()

        assertEquals(listOf("add", "info", "broken"), listed.map { it.name })
        assertEquals("READ_ONLY", listed[0].effectKind)
        assertEquals("UNKNOWN", listed[1].effectKind)
        assertEquals("""{"type":"object"}""", listed[0].inputSchemaJson)
        assertEquals("math", tools.providerId)
        assertEquals("Math", tools.displayName)
    }

    @Test
    fun textComesBackWrappedAndJsonAsItIs() = runBlocking {
        val sum = tools.callTool("add", """{"a":2,"b":3}""", context)
        val info = tools.callTool("info", "{}", context)

        assertTrue(sum.success)
        assertEquals("5.0", Json.parseToJsonElement(sum.resultJson).jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("""{"answer":42}""", info.resultJson)
    }

    @Test
    fun aFailingHandlerAnUnknownToolAndBadArgumentsFailOnlyThatCall() = runBlocking {
        val broken = tools.callTool("broken", "{}", context)
        val unknown = tools.callTool("nope", "{}", context)
        val bad = tools.callTool("add", "[1]", context)

        assertFalse(broken.success)
        assertTrue(broken.resultJson.contains("disk full"))
        assertTrue(unknown.resultJson.contains("UNKNOWN_TOOL"))
        assertTrue(bad.resultJson.contains("INVALID_ARGUMENTS"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aToolNameIsUsedOnce() {
        functionTools("x") {
            tool("a", "") { "" }
            tool("a", "") { "" }
        }
    }
}
