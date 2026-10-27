package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.tools.BackendToolClassification
import cc.noharry.agent.kernel.core.tools.MEMORY_HINT_ACTIVATION_SOURCE
import cc.noharry.agent.kernel.core.tools.McpToolRef
import cc.noharry.agent.kernel.core.tools.SESSION_INHERITED_ACTIVATION_SOURCE
import cc.noharry.agent.kernel.core.tools.ToolActivationMetadata
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivationStoreTest {
    @Test
    fun activationRecordsAreScopedByRunIdAndCarrySchema() {
        val store = ToolActivationStore { 123L }
        val tool = toolRef("set_temperature")

        store.recordCatalog("run-a", listOf(tool))
        store.activate(
            runId = "run-a",
            tool = tool,
            schemaJson = """{"type":"object"}""",
        )

        val activated = store.activated("run-a").single()
        assertEquals(qualifiedMcpTool("set_temperature"), activated.toolName)
        assertEquals("""{"type":"object"}""", activated.schemaJson)
        assertEquals(123L, activated.metadata.activatedAtEpochMillis)
        assertTrue(store.activated("run-b").isEmpty())
    }

    @Test
    fun knownInactiveClassificationUsesInjectedBackendName() {
        val store = ToolActivationStore()

        store.recordCatalog("run-a", listOf(toolRef("read_file")))

        val classification = store.classify(
            runId = "run-a",
            requestedModelToolName = qualifiedMcpTool("read_file"),
        )

        assertEquals(
            BackendToolClassification.KnownInactive(
                toolName = qualifiedMcpTool("read_file"),
                schemaPath = "/mcp/tools/${qualifiedMcpTool("read_file")}/schema.json",
            ),
            classification,
        )
    }

    @Test
    fun activatedToolsKeepFirstActivationOrderForPrefixCaching() {
        val store = ToolActivationStore()

        store.activate("run-a", toolRef("zeta"), """{"type":"object"}""")
        store.activate("run-a", toolRef("alpha"), """{"type":"object"}""")

        assertEquals(
            listOf(qualifiedMcpTool("zeta"), qualifiedMcpTool("alpha")),
            store.activated("run-a").map { it.toolName },
        )
    }

    @Test
    fun inactiveClassificationDoesNotReinjectActiveBackendNames() {
        val store = ToolActivationStore()
        val alpha = toolRef("alpha")
        store.recordCatalog("run-a", listOf(alpha, toolRef("cap_alpha")))
        store.activate("run-a", alpha, """{"type":"object"}""")

        val classification = store.classify(
            runId = "run-a",
            requestedModelToolName = qualifiedMcpTool("cap_alpha"),
        )

        assertEquals(
            BackendToolClassification.KnownInactive(
                toolName = qualifiedMcpTool("cap_alpha"),
                schemaPath = "/mcp/tools/${qualifiedMcpTool("cap_alpha")}/schema.json",
            ),
            classification,
        )
    }

    @Test
    fun clearRemovesRunActivationState() {
        val store = ToolActivationStore()
        store.activate("run-a", toolRef("set_temperature"), """{"type":"object"}""")

        store.clear("run-a")

        assertTrue(store.activated("run-a").isEmpty())
        assertEquals(
            BackendToolClassification.UnsupportedNativeFallback,
            store.classify("run-a", "set_temperature"),
        )
    }

    @Test
    fun memoryHintActivationsAreNotInheritedAcrossSessionRuns() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-a", "session-a")
        store.activate(
            runId = "run-a",
            tool = toolRef("set_temperature"),
            schemaJson = """{"type":"object"}""",
            metadata = ToolActivationMetadata(source = MEMORY_HINT_ACTIVATION_SOURCE),
        )

        store.snapshotSessionActivations("run-a", "session-a")
        store.inheritSessionActivations("run-b", "session-a")

        assertTrue(store.activated("run-b").isEmpty())
    }

    @Test
    fun inheritedSessionActivationsCarryInheritedProvenance() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-a", "session-a")
        store.activate("run-a", toolRef("set_temperature"), """{"type":"object"}""")

        store.snapshotSessionActivations("run-a", "session-a")
        store.inheritSessionActivations("run-b", "session-a")

        val inherited = store.activated("run-b").single()
        assertEquals(SESSION_INHERITED_ACTIVATION_SOURCE, inherited.metadata.source)
    }

    @Test
    fun laterRunsKeepEarlierToolsInPlaceAndAppendNewOnes() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-a", "session-a")
        store.activate("run-a", toolRef("set_temperature"), """{"type":"object"}""")
        store.activate("run-a", toolRef("set_fan"), """{"type":"object"}""")
        store.snapshotSessionActivations("run-a", "session-a")

        store.inheritSessionActivations("run-b", "session-a")
        store.activate("run-b", toolRef("set_fan"), """{"type":"object"}""")
        store.activate("run-b", toolRef("play_music"), """{"type":"object"}""")

        assertEquals(
            listOf("set_temperature", "set_fan", "play_music").map(::qualifiedMcpTool),
            store.activated("run-b").map { it.toolName },
        )
    }

    @Test
    fun inheritedToolsMissingFromTheCatalogAreDropped() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-a", "session-a")
        store.activate("run-a", toolRef("set_temperature"), """{"type":"object"}""")
        store.activate("run-a", toolRef("play_music"), """{"type":"object"}""")
        store.snapshotSessionActivations("run-a", "session-a")
        store.inheritSessionActivations("run-b", "session-a")

        store.dropInheritedOutside("run-b", setOf(qualifiedMcpTool("play_music")))
        assertEquals(listOf(qualifiedMcpTool("play_music")), store.activated("run-b").map { it.toolName })
        store.dropInheritedOutside("run-b", emptySet())
        assertEquals(1, store.activated("run-b").size)
    }

    @Test
    fun sessionPastItsCapKeepsTheNewestHalf() {
        val store = ToolActivationStore(maxSessionTools = 4)
        store.inheritSessionActivations("run-a", "session-a")
        listOf("a", "b", "c", "d").forEach { store.activate("run-a", toolRef(it), """{"type":"object"}""") }
        store.snapshotSessionActivations("run-a", "session-a")
        store.inheritSessionActivations("run-b", "session-a")
        assertEquals(4, store.activated("run-b").size)

        store.activate("run-b", toolRef("e"), """{"type":"object"}""")
        store.snapshotSessionActivations("run-b", "session-a")
        store.inheritSessionActivations("run-c", "session-a")
        assertEquals(listOf("d", "e").map(::qualifiedMcpTool), store.activated("run-c").map { it.toolName })
    }

    @Test
    fun explicitSchemaReadCannotEraseMemorySuppliedProvenance() {
        val store = ToolActivationStore()
        val tool = toolRef("set_temperature")
        store.inheritSessionActivations("run-a", "session-a")
        store.activate(
            runId = "run-a",
            tool = tool,
            schemaJson = """{"type":"object"}""",
            metadata = ToolActivationMetadata(source = MEMORY_HINT_ACTIVATION_SOURCE),
        )
        store.activate("run-a", tool, """{"type":"object","title":"refreshed"}""")

        val refreshed = store.activated("run-a").single()
        assertTrue(refreshed.metadata.suppliedByMemory)
        assertTrue(refreshed.metadata.source != MEMORY_HINT_ACTIVATION_SOURCE)

        store.snapshotSessionActivations("run-a", "session-a")
        store.inheritSessionActivations("run-b", "session-a")
        assertTrue(store.activated("run-b").isEmpty())
    }

    @Test
    fun olderRunCannotPublishAfterNewerRunStarts() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-older", "session-a")
        store.activate("run-older", toolRef("older_tool"), """{"type":"object"}""")

        store.inheritSessionActivations("run-newer", "session-a")
        store.snapshotSessionActivations("run-older", "session-a")
        store.inheritSessionActivations("run-next", "session-a")

        assertTrue(store.activated("run-next").isEmpty())
    }

    @Test
    fun olderMemoryOnlyTerminalCannotClearNewerRunCache() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-older", "session-a")
        store.activate(
            runId = "run-older",
            tool = toolRef("memory_tool"),
            schemaJson = """{"type":"object"}""",
            metadata = ToolActivationMetadata(source = MEMORY_HINT_ACTIVATION_SOURCE),
        )
        store.inheritSessionActivations("run-newer", "session-a")
        store.activate("run-newer", toolRef("newer_tool"), """{"type":"object"}""")

        store.snapshotSessionActivations("run-newer", "session-a")
        store.snapshotSessionActivations("run-older", "session-a")
        store.inheritSessionActivations("run-next", "session-a")

        assertEquals(
            listOf(qualifiedMcpTool("newer_tool")),
            store.activated("run-next").map { it.toolName },
        )
    }

    @Test
    fun memoryOnlyNewerSnapshotLeavesTombstoneAgainstOlderRun() {
        val store = ToolActivationStore()
        store.inheritSessionActivations("run-older", "session-a")
        store.activate("run-older", toolRef("older_tool"), """{"type":"object"}""")
        store.inheritSessionActivations("run-newer", "session-a")
        store.activate(
            runId = "run-newer",
            tool = toolRef("memory_tool"),
            schemaJson = """{"type":"object"}""",
            metadata = ToolActivationMetadata(source = MEMORY_HINT_ACTIVATION_SOURCE),
        )

        store.snapshotSessionActivations("run-newer", "session-a")
        store.inheritSessionActivations("run-next", "session-a")
        assertTrue(store.activated("run-next").isEmpty())

        store.snapshotSessionActivations("run-older", "session-a")
        store.inheritSessionActivations("run-verify", "session-a")

        assertTrue(store.activated("run-verify").isEmpty())
    }
}

private fun toolRef(name: String): McpToolRef =
    McpToolRef(
        provider = LoopMcpProvider(),
        serverId = TEST_MCP_SERVER_ID,
        normalizedServerId = TEST_MCP_SERVER_ID,
        originalToolName = name,
        normalizedToolName = name,
        qualifiedName = qualifiedMcpTool(name),
        descriptor = mcpTool(name),
    )
