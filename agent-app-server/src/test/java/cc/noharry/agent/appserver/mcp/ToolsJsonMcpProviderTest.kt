package cc.noharry.agent.appserver.mcp
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolCallContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsJsonMcpProviderTest {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresBoundedThreadSafeConcurrency() {
        val provider = ToolsJsonMcpProvider(requireNotNull(parseMcpCatalog(fullToolsJson())))
        assertEquals(ProviderConcurrencyDeclaration.threadSafe(), provider.providerConcurrency)
    }

    @Test
    fun staticCatalogIdentityIsVersioned() {
        val provider = ToolsJsonMcpProvider(requireNotNull(parseMcpCatalog(fullToolsJson())))
        assertEquals("app-server:tools_json:v1", provider.catalogCacheIdentity)
    }

    @Test
    fun catalogExposesOnlyToolsFromJson() = runBlocking {
        val catalog = requireNotNull(parseMcpCatalog(fullToolsJson()))
        val provider = ToolsJsonMcpProvider(catalog)

        val tools = provider.listTools()
        val names = tools.map { it.name }
        val schema = tools.single { it.name == "command_ac_TemperatureAdjust" }.inputSchemaJson

        assertTrue(names.contains("command_ac_TemperatureAdjust"))
        assertFalse(names.contains("debug_echo"))
        assertTrue(schema.contains("\"value\""))
        assertTrue(schema.contains("\"SPOT\""))
        assertTrue(schema.contains("\"set\""))
        assertEquals(4, catalog.tools.size)
    }

    @Test
    fun unknownToolFailsInsteadOfActingAsFallback() = runBlocking {
        val provider = ToolsJsonMcpProvider(requireNotNull(parseMcpCatalog(fullToolsJson())))

        val result = provider.callTool(
            toolName = "debug_echo",
            argumentsJson = """{"text":"空调调到20度"}""",
            context = ToolCallContext("session", "run", "request"),
        )

        assertFalse(result.success)
        assertTrue(result.resultJson.contains("UNKNOWN_DEBUG_TOOL"))
        assertEquals(false, result.trace?.validationOk)
    }

    @Test
    fun schemaValidationFailureReturnsFailedMcpCall() = runBlocking {
        val provider = ToolsJsonMcpProvider(requireNotNull(parseMcpCatalog(fullToolsJson())))

        val result = provider.callTool(
            toolName = "command_ac_TemperatureAdjust",
            argumentsJson = """{"action":"set"}""",
            context = ToolCallContext("session", "run", "request"),
        )

        assertFalse(result.success)
        assertTrue(result.resultJson.contains("SCHEMA_VALIDATION_FAILED"))
    }

    @Test
    fun validToolCallReturnsTrace() = runBlocking {
        val provider = ToolsJsonMcpProvider(requireNotNull(parseMcpCatalog(fullToolsJson())))

        val result = provider.callTool(
            toolName = "command_ac_TemperatureAdjust",
            argumentsJson = """{"action":"set","ref":"set","refType":"SPOT","value":20}""",
            context = ToolCallContext("session", "run", "request"),
        )

        assertTrue(result.success)
        assertTrue(result.resultJson.contains("tools.json provider accepted"))
        assertEquals(true, result.trace?.validationOk)
    }
}

private fun fullToolsJson(): String =
    """
        {
          "jsonrpc": "2.0",
          "result": {
            "tools": [
              {
                "name": "command_app_PlayTts",
                "title": "TTS",
                "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}
              },
              {
                "name": "command_music_Play",
                "title": "Play music",
                "inputSchema": {"type": "object"}
              },
              {
                "name": "command_navi_SearchPoi",
                "title": "Search POI",
                "inputSchema": {"type": "object"}
              },
              {
                "name": "command_ac_TemperatureAdjust",
                "title": "Set AC temperature",
                "inputSchema": {
                  "type": "object",
                  "required": ["action", "ref", "refType", "value"],
                  "properties": {
                    "action": {"type": "string", "enum": ["set"]},
                    "ref": {"type": "string", "enum": ["set"]},
                    "refType": {"type": "string", "enum": ["SPOT"]},
                    "value": {"type": "number"}
                  }
                }
              }
            ]
          }
        }
    """.trimIndent()
