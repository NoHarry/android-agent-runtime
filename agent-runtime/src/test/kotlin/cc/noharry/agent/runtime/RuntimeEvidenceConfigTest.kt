@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class RuntimeEvidenceConfigTest {
    @Test
    fun noneWithNullHashIsValid() {
        val root = Files.createTempDirectory("ev-none").toFile()
        try {
            val v = validateRuntimeEvidenceConfig(
                RuntimeEvidenceConfig(storageRoot = root, casePackId = "none", promptSha256 = null),
            )
            assertEquals("none", v.casePackId)
            assertNull(v.promptSha256)
            assertFalse(v.probesEnabled)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noneWithNonEmptyHashRejected() {
        val root = Files.createTempDirectory("ev-none-hash").toFile()
        try {
            expectIllegal {
                validateRuntimeEvidenceConfig(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        casePackId = "none",
                        promptSha256 = "a".repeat(64),
                    ),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nonNoneRequiresLowercase64Hex() {
        val root = Files.createTempDirectory("ev-hash").toFile()
        try {
            expectIllegal {
                validateRuntimeEvidenceConfig(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("bench_core"),
                        casePackId = "host.bench.v1",
                        promptSha256 = null,
                    ),
                )
            }
            expectIllegal {
                validateRuntimeEvidenceConfig(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("bench_core"),
                        casePackId = "host.bench.v1",
                        promptSha256 = "A".repeat(64),
                    ),
                )
            }
            val ok = validateRuntimeEvidenceConfig(
                RuntimeEvidenceConfig(
                    storageRoot = root,
                    enabledProbePackIds = setOf("bench_core"),
                    casePackId = "host.bench.v1",
                    promptSha256 = "ab".repeat(32),
                ),
            )
            assertEquals("ab".repeat(32), ok.promptSha256)
            assertTrue(ok.probesEnabled)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blankCasePackIdRejected() {
        val root = Files.createTempDirectory("ev-blank").toFile()
        try {
            expectIllegal {
                validateRuntimeEvidenceConfig(
                    RuntimeEvidenceConfig(storageRoot = root, casePackId = "  "),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownPackIdRejected() {
        val root = Files.createTempDirectory("ev-pack").toFile()
        try {
            expectIllegal {
                validateRuntimeEvidenceConfig(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("not_a_real_pack"),
                    ),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicatePackIdsDedupedAndSorted() {
        val root = Files.createTempDirectory("ev-dup").toFile()
        try {
            val v = validateRuntimeEvidenceConfig(
                RuntimeEvidenceConfig(
                    storageRoot = root,
                    enabledProbePackIds = setOf("bench_core", "bench_core"),
                ),
            )
            assertEquals(listOf("bench_core"), v.enabledProbePackIds)
            assertEquals(1, v.resolvePacks().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun builderRuntimeEvidenceAppliesRootAndPacks() {
        val root = Files.createTempDirectory("ev-builder").toFile()
        try {
            val builder = AgentRuntimeBuilder.create().runtimeEvidence(
                RuntimeEvidenceConfig(
                    storageRoot = root,
                    enabledProbePackIds = setOf("bench_core"),
                    casePackId = "none",
                ),
            )
            // build() must succeed; evidence is stored for DefaultAgentRuntime
            builder.build()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publicSignatureDoesNotExposeInternalProbeTypes() {
        val method = AgentRuntimeBuilder::class.java.methods.first { it.name == "runtimeEvidence" }
        assertEquals(1, method.parameterTypes.size)
        assertEquals(RuntimeEvidenceConfig::class.java, method.parameterTypes[0])
        val forbidden = listOf("ProbePack", "CaseBinding", "ProbeRunAttachment", "ProbeSession")
        val sig = method.toGenericString()
        for (name in forbidden) {
            assertFalse("signature leaks $name: $sig", sig.contains(name))
        }
    }

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
