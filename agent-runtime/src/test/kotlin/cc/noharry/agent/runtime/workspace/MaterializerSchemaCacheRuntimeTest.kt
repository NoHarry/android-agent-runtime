@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaCacheEntry
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaMetadata
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.TEST_MCP_SERVER_ID
import cc.noharry.agent.runtime.agentRequest
import cc.noharry.agent.runtime.qualifiedMcpTool
import cc.noharry.agent.runtime.request
import cc.noharry.agent.runtime.round
import cc.noharry.agent.runtime.runtime
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MaterializerSchemaCacheRuntimeTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun reliabilityStorageRootEnablesSchemaCacheAcrossRuntimeInstances() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-schema-cache-").toFile()
        val workspaceRoot = Files.createTempDirectory("agent-runtime-workspace-").toFile()
        val firstBackend = RuntimeSchemaBackend()

        runtime(storageRoot, workspaceRoot, firstBackend, SchemaReadModel("first"))
            .run(agentRequest("request-1"))
            .toList()

        val secondBackend = RuntimeSchemaBackend().apply { schemaRevision = 2 }
        val secondModel = SchemaReadModel("second")
        runtime(storageRoot, workspaceRoot, secondBackend, secondModel)
            .run(agentRequest("request-2"))
            .toList()
        val marker = File(storageRoot, MATERIALIZER_SCHEMA_CACHE_MARKER_FILE).readText()

        assertTrue(secondModel.seenRequests[1].messages.last().content.contains("cache_hit\":true"))
        assertTrue(marker.contains("schemaCacheMode=FILE_BACKED"))
        assertTrue(marker.contains("schemaCacheEntryCount=1"))
    }

    @Test
    fun fileBackedSchemaCacheConcurrentWritesCleanExpiredEntriesSafely() = runBlocking {
        val root = Files.createTempDirectory("agent-runtime-file-schema-cache-").toFile()
        val cache = FileBackedMaterializerSchemaCache(root)
        val expired = schemaCacheEntry(expiresAtEpochMillis = 1_000L)
        val fresh = schemaCacheEntry(expiresAtEpochMillis = 10_000L)
        repeat(32) { index ->
            cache.write("expired-$index", expired, nowEpochMillis = 0L)
        }

        coroutineScope {
            (0 until 16).map { index ->
                async(Dispatchers.Default) {
                    cache.write("fresh-$index", fresh, nowEpochMillis = 2_000L)
                }
            }.awaitAll()
        }

        val remaining = File(root, MATERIALIZER_SCHEMA_CACHE_DIR)
            .listFiles()
            .orEmpty()
            .count { it.isFile && it.extension == "json" }
        assertEquals(16, remaining)
    }

    private fun runtime(
        storageRoot: File,
        workspaceRoot: File,
        backend: McpClientProvider,
        model: ModelClient,
    ) = AgentRuntimeBuilder.create()
        .workspaceRoot(workspaceRoot)
        .reliabilityStorageRoot(storageRoot)
        .toolProvider(backend)
        .modelClient(model)
        .build()

    private fun agentRequest(requestId: String): AgentRequest = AgentRequest(
        requestId = requestId,
        sessionId = "session-1",
        input = "read schema",
    )
}

private fun schemaCacheEntry(expiresAtEpochMillis: Long): MaterializedSchemaCacheEntry =
    MaterializedSchemaCacheEntry(
        content = """{"type":"object"}""",
        metadata = MaterializedSchemaMetadata(
            schemaVersion = "test",
            schemaHash = "test",
            expiresAtEpochMillis = expiresAtEpochMillis,
        ),
    )

private class SchemaReadModel(
    private val response: String,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    val seenRequests = mutableListOf<ModelRequest>()
    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        seenRequests += request
        val events = if (round == 0) {
            listOf(
                ModelEvent.ToolCall(
                    id = "tool-1",
                    name = "read_file",
                    argumentsJson = """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
                ),
                ModelEvent.Completed,
            )
        } else {
            listOf(
                ModelEvent.ToolCall(
                    id = "tool-2",
                    name = "finish",
                    argumentsJson = """{"response":"$response"}""",
                ),
                ModelEvent.Completed,
            )
        }
        round += 1
        return flowOf(*events.toTypedArray())
    }
}

private class RuntimeSchemaBackend : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    var schemaRevision = 1

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            ToolDescriptor(
                name = "set_temperature",
                description = "Set temperature",
                inputSchemaJson = """{"type":"object","revision":$schemaRevision}""",
            ),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}
