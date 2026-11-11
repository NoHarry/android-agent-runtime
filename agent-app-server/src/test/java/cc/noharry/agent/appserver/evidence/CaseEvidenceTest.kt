@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.evidence
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseEvidenceTest {
    @Test
    fun promptHashMatchesDeterministicBenchPrompt() {
        assertEquals(
            SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256,
            sha256HexUtf8(SAMPLE_DEBUG_CASE_PACK_PROMPT),
        )
    }

    @Test
    fun evidenceConfigHashesThePassedRequestPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev").toFile()
        try {
            val cfg = caseEvidenceConfig(filesDir, SAMPLE_DEBUG_CASE_PACK_PROMPT)
            assertEquals(SAMPLE_DEBUG_CASE_PACK_ID, cfg.casePackId)
            assertEquals(sha256HexUtf8(SAMPLE_DEBUG_CASE_PACK_PROMPT), cfg.promptSha256)
            assertEquals(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256, cfg.promptSha256)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun evidenceConfigRejectsNonBenchPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev-bad").toFile()
        try {
            try {
                caseEvidenceConfig(filesDir, "hello ordinary user")
                org.junit.Assert.fail("expected reject")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun evidenceConfigRejectsWhitespacePaddedBenchPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev-pad").toFile()
        try {
            val padded = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "
            assertFalse(isCasePrompt(padded))
            try {
                caseEvidenceConfig(filesDir, padded)
                org.junit.Assert.fail("padded prompt must not bind")
            } catch (_: IllegalArgumentException) {
                // expected — would otherwise store e3ab5758... while input hashes to another value
            }
            assertTrue(sha256HexUtf8(padded) != SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun onlyExactBenchPromptIsBench() {
        assertTrue(isCasePrompt(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        assertTrue(productionCaseBoundDecision(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        // Counterexamples: leading/trailing whitespace must NOT match (byte identity).
        assertFalse(isCasePrompt("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
        assertFalse(isCasePrompt(" $SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isCasePrompt("$SAMPLE_DEBUG_CASE_PACK_PROMPT "))
        assertFalse(isCasePrompt("\t$SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isCasePrompt("finish with done"))
        assertFalse(isCasePrompt(""))
        assertFalse(productionCaseBoundDecision("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
    }
}
