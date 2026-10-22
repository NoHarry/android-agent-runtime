@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.probe

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.kernel.api.RuntimeProbeEmitterHub
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.runtime.RuntimeProbeEmitterHubTestAccess
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Probe hub isolation: registration keys include runtime instance identity + runId,
 * CAS uninstall, closeAll cleanup, and disk evidence isolation for same requestId.
 */
class RuntimeProbeEmitterHubIsolationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun install_handle_cas_uninstall_and_old_handle_cannot_remove_new() {
        RuntimeProbeEmitterHubTestAccess.clearAll()
        try {
            val a =
                RuntimeProbeEmitterHub.install("rt-old", "run-1") { _, _ -> true }
            val b =
                RuntimeProbeEmitterHub.install("rt-new", "run-1") { _, _ -> true }
            assertEquals(2, RuntimeProbeEmitterHubTestAccess.sizeForTest())
            // Exact composite keys only — no runId-only latest map.
            assertNotNull(RuntimeProbeEmitterHub.emitterFor("rt-new", "run-1"))
            assertNotNull(RuntimeProbeEmitterHub.emitterFor("rt-old", "run-1"))

            // Old handle must not remove the new registration for a different composite key.
            assertTrue(RuntimeProbeEmitterHub.uninstall(a))
            assertNull(RuntimeProbeEmitterHub.emitterFor("rt-old", "run-1"))
            assertNotNull(RuntimeProbeEmitterHub.emitterFor("rt-new", "run-1"))
            assertEquals(1, RuntimeProbeEmitterHubTestAccess.sizeForTest())

            // Reinstall same composite key with new token; stale handle fails CAS.
            val b2 =
                RuntimeProbeEmitterHub.install("rt-new", "run-1") { _, _ -> true }
            assertFalse(RuntimeProbeEmitterHub.uninstall(b))
            assertNotNull(RuntimeProbeEmitterHub.emitterFor("rt-new", "run-1"))
            assertTrue(RuntimeProbeEmitterHub.uninstall(b2))
            assertEquals(0, RuntimeProbeEmitterHubTestAccess.sizeForTest())
        } finally {
            RuntimeProbeEmitterHubTestAccess.clearAll()
        }
    }

    @Test
    fun uninstall_all_for_runtime_leaves_other_runtime_entries() {
        RuntimeProbeEmitterHubTestAccess.clearAll()
        try {
            RuntimeProbeEmitterHub.install("rt-a", "run-x") { _, _ -> true }
            RuntimeProbeEmitterHub.install("rt-b", "run-x") { _, _ -> true }
            RuntimeProbeEmitterHub.install("rt-a", "run-y") { _, _ -> true }
            RuntimeProbeEmitterHub.uninstallAllForRuntime("rt-a")
            assertNull(RuntimeProbeEmitterHub.emitterFor("rt-a", "run-x"))
            assertNull(RuntimeProbeEmitterHub.emitterFor("rt-a", "run-y"))
            assertNotNull(RuntimeProbeEmitterHub.emitterFor("rt-b", "run-x"))
            assertEquals(1, RuntimeProbeEmitterHubTestAccess.sizeForTest())
        } finally {
            RuntimeProbeEmitterHubTestAccess.clearAll()
        }
    }

    @Test
    fun two_runtimes_same_request_id_disk_evidence_isolated_and_close_clears_hub() =
        runBlocking {
            RuntimeProbeEmitterHubTestAccess.clearAll()
            val rootA = temp.newFolder("probe-a")
            val rootB = temp.newFolder("probe-b")
            val runtimeA = buildProbeRuntime(rootA)
            val runtimeB = buildProbeRuntime(rootB)
            try {
                // Same requestId / runId string; separate Runtime instances → separate roots.
                runtimeA.run(AgentRequest("shared-req", "s", "hello-a")).toList()
                runtimeB.run(AgentRequest("shared-req", "s", "hello-b")).toList()
            } finally {
                runtimeA.close()
                runtimeB.close()
            }
            assertEquals(0, RuntimeProbeEmitterHubTestAccess.sizeForTest())
            val filesA = rootA.walkTopDown().filter { it.isFile }.map { it.relativeTo(rootA).path }.toSet()
            val filesB = rootB.walkTopDown().filter { it.isFile }.map { it.relativeTo(rootB).path }.toSet()
            assertTrue("runtime A must write evidence under its root", filesA.isNotEmpty())
            assertTrue("runtime B must write evidence under its root", filesB.isNotEmpty())
            // No cross-root leakage of the other runtime's content.
            val textA = collectText(rootA)
            val textB = collectText(rootB)
            assertFalse(textA.any { it.contains("hello-b") && it.contains("shared-req") && it.contains("input") })
            assertFalse(textB.any { it.contains("hello-a") && it.contains("shared-req") && it.contains("input") })
        }

    @Test
    fun close_is_idempotent_and_hub_empty_after_probe_run() =
        runBlocking {
            RuntimeProbeEmitterHubTestAccess.clearAll()
            val root = temp.newFolder("probe-close")
            val runtime = buildProbeRuntime(root)
            runtime.run(AgentRequest("c1", "s", "hi")).toList()
            runtime.close()
            runtime.close()
            assertEquals(0, RuntimeProbeEmitterHubTestAccess.sizeForTest())
        }

    private fun buildProbeRuntime(probeRoot: File) =
        AgentRuntimeBuilder.create()
            .modelClient(finishClient())
            .runtimeEvidence(
                RuntimeEvidenceConfig(
                    storageRoot = probeRoot,
                    enabledProbePackIds = setOf("bench_core"),
                    casePackId = "none",
                    promptSha256 = null,
                ),
            )
            .nowEpochMillis { 1_700_000_000_000L }
            .build()

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
