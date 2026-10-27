package cc.noharry.agent.kernel.core.tools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolDefinitionsTest {
    @Test
    fun nativeToolSchemasAreProviderValidObjectSchemas() {
        NATIVE_TOOLS.forEach { tool ->
            val schema = Json.parseToJsonElement(tool.schemaJson) as? JsonObject
            assertNotNull("${tool.name} schema must be a JSON object.", schema)
            assertEquals("object", schema?.string("type"))
            assertTrue("${tool.name} schema must describe the tool.", !schema?.string("description").isNullOrBlank())
            assertRequiredFieldsExist(tool.name, schema)
        }
    }

    @Test
    fun nativeToolSchemaPropertiesAreDescribed() {
        NATIVE_TOOLS.forEach { tool ->
            assertDescribedProperties(tool.name, tool.schemaObject())
        }
    }

    @Test
    fun finishSchemaDefinesRequiredResponseProperty() {
        val schema = NATIVE_TOOLS.single { it.name == "finish" }.schemaObject()
        val properties = schema.obj("properties")
        val response = properties?.obj("response")
        assertNotNull("finish properties must be defined.", properties)
        assertEquals("string", response?.string("type"))
        assertTrue(schema.string("description").orEmpty().contains("exactly once"))
        assertTrue(schema.string("description").orEmpty().contains("直接展示给用户的完整最终答复"))
        assertTrue(response?.string("description").orEmpty().contains("直接展示给用户的完整最终答复"))
        assertTrue(response?.string("description").orEmpty().contains("不是过程旁白"))
        assertTrue(response?.string("description").orEmpty().contains("不再有后续输出机会"))
        assertTrue(!schema.string("description").orEmpty().contains("finish later"))
    }

    @Test
    fun updateTodoSchemaIsCompactAndMentionsEcho() {
        val description = NATIVE_TOOLS.single { it.name == "update_todo" }
            .schemaObject()
            .string("description")
            .orEmpty()

        assertTrue(description.contains("alongside related backend or workspace tool calls in the same round"))
        assertTrue(description.contains("echoes the full normalized todo list"))
        assertTrue(!description.contains("Never"))
        assertTrue(!description.contains("never"))
        assertTrue(!description.contains("finish later"))
    }

    @Test
    fun nativeToolsIncludeWorkspaceWriteSchemas() {
        val names = NATIVE_TOOLS.map { it.name }

        assertTrue(names.contains("write_file"))
        assertTrue(names.contains("edit_file"))
        assertTrue(WORKSPACE_TOOL_NAMES.contains("write_file"))
        assertTrue(WORKSPACE_TOOL_NAMES.contains("edit_file"))
    }

    private fun assertRequiredFieldsExist(toolName: String, schema: JsonObject?) {
        val properties = schema?.obj("properties") ?: return
        schema.array("required").orEmpty().forEach { element ->
            val field = element.jsonPrimitive.contentOrNull
            assertTrue("$toolName required field is missing from properties: $field", properties.containsKey(field))
        }
    }

    private fun cc.noharry.agent.kernel.api.model.ModelTool.schemaObject(): JsonObject =
        Json.parseToJsonElement(schemaJson) as JsonObject

    private fun assertDescribedProperties(toolName: String, schema: JsonObject) {
        schema.obj("properties")?.forEach { (name, value) ->
            val property = value as? JsonObject
            assertNotNull("$toolName property must be an object: $name", property)
            assertTrue(
                "$toolName property must include description: $name",
                !property?.string("description").isNullOrBlank(),
            )
            property?.let { assertDescribedProperties("$toolName.$name", it) }
        }
        (schema.obj("items"))?.let { assertDescribedProperties("$toolName.items", it) }
    }
}

private fun JsonObject.obj(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? =
    this[name] as? JsonArray

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
