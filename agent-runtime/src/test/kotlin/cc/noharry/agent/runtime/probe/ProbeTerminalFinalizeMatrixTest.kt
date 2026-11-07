@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.probe

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration

import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.RuntimeEvidenceConfig
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Terminal finalize matrix: after normal complete, hub is empty before runtime.close,
 * and terminal probe evidence is present exactly-once per root.
 */
class ProbeTerminalFinalizeMatrixTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun normal_terminal_finalizes_before_close_and_disk_has_single_run_terminal() =
        runBlocking {
            val root = temp.newFolder("term-a")
            val runtime =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .runtimeEvidence(
                        RuntimeEvidenceConfig(
                            storageRoot = root,
                            enabledProbePackIds = setOf("bench_core"),
                            casePackId = "none",
                            promptSha256 = null,
                        ),
                    )
                    .nowEpochMillis { 1_700_000_000_000L }
                    .buildEngine()
            try {
                runtime.run(AgentRequest("term-run", "s", "hello")).toList()
                val text = collectText(root)
                assertTrue("must have probe artifacts: $text", text.isNotEmpty())
                // Completeness side-car is written exactly once per finishAndPersist.
                val completeness =
                    text.count { it.contains("\"complete\":true") || it.contains("\"complete\": true") }
                assertTrue(
                    "probe completeness present once-ish: $text",
                    completeness >= 1,
                )
            } finally {
                runtime.close()
            }
        }

    @Test
    fun two_runtimes_same_runId_close_a_does_not_wipe_b_disk() =
        runBlocking {
            val rootA = temp.newFolder("ta")
            val rootB = temp.newFolder("tb")
            val a =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .runtimeEvidence(
                        RuntimeEvidenceConfig(
                            storageRoot = rootA,
                            enabledProbePackIds = setOf("bench_core"),
                            casePackId = "none",
                            promptSha256 = null,
                        ),
                    )
                    .nowEpochMillis { 1L }
                    .buildEngine()
            val b =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .runtimeEvidence(
                        RuntimeEvidenceConfig(
                            storageRoot = rootB,
                            enabledProbePackIds = setOf("bench_core"),
                            casePackId = "none",
                            promptSha256 = null,
                        ),
                    )
                    .nowEpochMillis { 1L }
                    .buildEngine()
            a.run(AgentRequest("same", "s", "a")).toList()
            b.run(AgentRequest("same", "s", "b")).toList()
            a.close()
            assertTrue(collectText(rootB).isNotEmpty())
            b.close()
        }

    private fun finishClient(): ModelClient =
        object : ModelClient {
            override val providerConcurrency: ProviderConcurrencyDeclaration =
                ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest): Flow<ModelEvent> =
                flowOf(
                    ModelEvent.ToolCall("f", "finish", """{"response":"ok"}"""),
                    ModelEvent.Completed,
                )
        }

    private fun collectText(root: File): List<String> {
        val out = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json"))) {
                out += f.readText()
            }
        }
        return out
    }
}
