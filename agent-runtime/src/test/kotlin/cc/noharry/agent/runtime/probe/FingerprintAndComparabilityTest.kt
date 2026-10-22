package cc.noharry.agent.runtime.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class FingerprintAndComparabilityTest {

    @Test
    fun schemaCanonicalEmitsExactDecimalNotIeee() {
        val tree = mapOf(
            "maximum" to BigDecimal("32"),
            "minimum" to FingerprintCanonicalizer.parseExactDecimalToken("0.5"),
        )
        val text = FingerprintCanonicalizer.schemaCanonical(tree)
        assertTrue(text.contains("\"minimum\":0.5"))
        assertTrue(text.contains("\"maximum\":32"))
        // Key order sorted
        assertTrue(text.indexOf("maximum") < text.indexOf("minimum") || text.indexOf("\"maximum\"") < text.indexOf("\"minimum\""))
    }

    @Test
    fun largeIntegerTokenPreservedWithoutDoubleRounding() {
        val token = "9007199254740993"
        val d = FingerprintCanonicalizer.parseExactDecimalToken(token)
        assertEquals(token, FingerprintCanonicalizer.emitExactDecimal(d))
        val digest = FingerprintCanonicalizer.schemaDigest(mapOf("n" to d))
        assertEquals(64, digest.length)
    }

    @Test
    fun expAndTrailingZerosNormalized() {
        assertEquals("0.15", FingerprintCanonicalizer.emitExactDecimal(BigDecimal("1.5e-1")))
        assertEquals("32", FingerprintCanonicalizer.emitExactDecimal(BigDecimal("32.0")))
        assertEquals("1.5", FingerprintCanonicalizer.emitExactDecimal(BigDecimal("1.50")))
    }

    @Test
    fun configCanonicalRejectsFloat() {
        try {
            FingerprintCanonicalizer.configCanonical(mapOf("x" to 0.5))
            org.junit.Assert.fail("expected error")
        } catch (_: Exception) {
            // ok
        }
    }

    @Test
    fun sameResolvedConfigSameFingerprints() {
        val a = sampleMeta(armId = "a", provider = "p1")
        val b = sampleMeta(armId = "b", provider = "p1")
        assertEquals(a.configFingerprint, b.configFingerprint)
        assertEquals(a.controlFingerprint, b.controlFingerprint)
        assertTrue(a.comparable)
    }

    @Test
    fun providerAbComparableWhenDeclaredVariablesSet() {
        val a = sampleMeta(
            armId = "arm-a",
            provider = "deepseek",
            protocol = "openai",
            model = "m1",
            baseUrlHost = "api.a.example",
            declaredVariables = listOf("provider", "protocol", "model", "baseUrlHost"),
        )
        val b = sampleMeta(
            armId = "arm-b",
            provider = "other",
            protocol = "anthropic",
            model = "m2",
            baseUrlHost = "api.b.example",
            declaredVariables = listOf("provider", "protocol", "model", "baseUrlHost"),
        )
        assertTrue(a.comparable && b.comparable)
        assertEquals(a.controlFingerprint, b.controlFingerprint)
        assertTrue(a.configFingerprint != b.configFingerprint)
        assertTrue(compareArms(listOf(a, b)).isEmpty())
    }

    @Test
    fun providerAbNotComparableWhenDeclaredVariablesEmpty() {
        val a = sampleMeta(armId = "a", provider = "p1", declaredVariables = emptyList())
        val b = sampleMeta(armId = "b", provider = "p2", declaredVariables = emptyList())
        val reasons = compareArms(listOf(a, b))
        assertTrue(reasons.any { it.startsWith("R4:") })
    }

    @Test
    fun forbiddenVariableIdMakesMetaNotComparable() {
        val meta = sampleMeta(declaredVariables = listOf("search"))
        assertFalse(meta.comparable)
        assertTrue(meta.incompleteReasons.any { it.contains("forbidden") || it.contains("search") })
    }

    @Test
    fun schemaErrorDigestMakesMetaNotComparable() {
        val meta = buildRunMeta(
            runId = "run-bad-schema",
            sessionId = "sess",
            armId = "default",
            buildId = "build-1",
            startedAtEpochMillis = 1L,
            provider = "prov",
            protocol = "openai",
            model = "model-x",
            baseUrlHost = "api.example.com",
            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
            caseBinding = CaseBinding("none"),
            enabledProbePacks = listOf("bench_core"),
            toolset = listOf(
                ToolCatalogEntry("read_file", "NATIVE", "good-digest"),
                ToolCatalogEntry("broken_tool", "BACKEND", SCHEMA_DIGEST_ERROR),
            ),
        )
        assertFalse("A6: schema-error toolset must not be comparable", meta.comparable)
        assertTrue(
            "expected toolset incomplete reason, got ${meta.incompleteReasons}",
            meta.incompleteReasons.any { it.contains("toolset incomplete") && it.contains("broken_tool") },
        )
    }

    @Test
    fun invalidSchemaJsonProducesSchemaErrorDigest() {
        val entry = toolCatalogEntry("bad", "BACKEND", "{not-json")
        assertEquals(SCHEMA_DIGEST_ERROR, entry.schemaDigest)
        val meta = buildRunMeta(
            runId = "run-parse-fail",
            sessionId = "sess",
            armId = "default",
            buildId = "build-1",
            startedAtEpochMillis = 1L,
            provider = "prov",
            protocol = "openai",
            model = "m",
            baseUrlHost = "local",
            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
            caseBinding = CaseBinding("none"),
            enabledProbePacks = emptyList(),
            toolset = listOf(entry),
        )
        assertFalse(meta.comparable)
    }

    private fun sampleMeta(
        armId: String = "default",
        provider: String = "prov",
        protocol: String = "openai",
        model: String = "model-x",
        baseUrlHost: String = "api.example.com",
        declaredVariables: List<String> = emptyList(),
    ): RunMeta =
        buildRunMeta(
            runId = "run-$armId",
            sessionId = "sess",
            armId = armId,
            buildId = "build-1",
            startedAtEpochMillis = 1L,
            provider = provider,
            protocol = protocol,
            model = model,
            baseUrlHost = baseUrlHost,
            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
            caseBinding = CaseBinding("none"),
            enabledProbePacks = listOf("bench_core"),
            toolset = listOf(
                ToolCatalogEntry("mcp__x__t", "BACKEND", "abc"),
            ),
            declaredVariables = declaredVariables,
        )
}
