@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeActionOutcome
import cc.noharry.agent.kernel.api.RuntimeActionStatus
import cc.noharry.agent.kernel.api.RuntimeCommitStatus
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeObligationLedgerCodecTest {
    @Test
    fun isSatisfiedAcceptsCommittedAndNoChangeRequiredOnly() {
        assertTrue(
            outcome(RuntimeActionStatus.SUCCEEDED, RuntimeCommitStatus.COMMITTED).isSatisfied(),
        )
        assertTrue(
            outcome(RuntimeActionStatus.SUCCEEDED, RuntimeCommitStatus.NO_CHANGE_REQUIRED).isSatisfied(),
        )
        assertFalse(
            outcome(RuntimeActionStatus.SUCCEEDED, RuntimeCommitStatus.NOT_COMMITTED).isSatisfied(),
        )
        assertFalse(
            outcome(RuntimeActionStatus.FAILED, RuntimeCommitStatus.COMMITTED).isSatisfied(),
        )
        assertFalse(
            outcome(RuntimeActionStatus.DENIED, RuntimeCommitStatus.NOT_COMMITTED).isSatisfied(),
        )
    }

    @Test
    fun satisfiedNoChangeRequiredIsNotOverwrittenByLaterFailure() {
        val ledger = RuntimeObligationLedger("secret")
        val descriptor = requiredDescriptor()
        val declared = ledger.declareRequiredMutating("run-1", descriptor, """{"value":"home"}""")!!
        ledger.recordOutcome(
            declared.copy(
                status = RuntimeActionStatus.SUCCEEDED,
                commitStatus = RuntimeCommitStatus.NO_CHANGE_REQUIRED,
            ),
        )
        ledger.recordOutcome(
            declared.copy(
                status = RuntimeActionStatus.FAILED,
                commitStatus = RuntimeCommitStatus.NOT_COMMITTED,
                safeCode = "WRITE_FAILED",
            ),
        )
        val kept = ledger.snapshot().single()
        assertTrue(kept.isSatisfied())
        assertEquals(RuntimeCommitStatus.NO_CHANGE_REQUIRED, kept.commitStatus)
        assertEquals(0, ledger.summarize(RuntimeCompletionOutcome.SUCCEEDED).requiredUnresolved)
    }

    @Test
    fun differentProviderActionsWithSameEffectDigestDoNotAlias() {
        val ledger = RuntimeObligationLedger("secret")
        val sharedDigest = "same-effect-digest"
        val first = requiredDescriptor("provider-a", "remember_fact", sharedDigest)
        val second = requiredDescriptor("provider-b", "forget_fact", sharedDigest)
        val declaredA = ledger.declareRequiredMutating("run-1", first, """{"value":"home"}""")!!
        val declaredB = ledger.declareRequiredMutating("run-1", second, """{"value":"home"}""")!!
        assertTrue(declaredA.obligationId != declaredB.obligationId)
        ledger.recordOutcome(
            declaredA.copy(
                status = RuntimeActionStatus.SUCCEEDED,
                commitStatus = RuntimeCommitStatus.COMMITTED,
            ),
        )
        val afterFirst = ledger.snapshot().associateBy { it.obligationId }
        assertTrue(afterFirst.getValue(declaredA.obligationId).isSatisfied())
        assertFalse(afterFirst.getValue(declaredB.obligationId).isSatisfied())

        val retrySame = ledger.declareRequiredMutating("run-1", first, """{"value":"home"}""")!!
        assertEquals(declaredA.obligationId, retrySame.obligationId)
        assertTrue(retrySame.isSatisfied())
        ledger.recordOutcome(
            declaredB.copy(
                status = RuntimeActionStatus.FAILED,
                commitStatus = RuntimeCommitStatus.NOT_COMMITTED,
                safeCode = "WRITE_FAILED",
            ),
        )
        val afterRetry = ledger.snapshot().associateBy { it.obligationId }
        assertTrue(afterRetry.getValue(declaredA.obligationId).isSatisfied())
        assertFalse(afterRetry.getValue(declaredB.obligationId).isSatisfied())
    }

    @Test
    fun encodeDecodeRoundtripPreservesItems() {
        val ledger = RuntimeObligationLedger("roundtrip-secret")
        val descriptor = requiredDescriptor()
        val declared = ledger.declareRequiredMutating("run-1", descriptor, """{"value":"home"}""")!!
        ledger.recordOutcome(
            declared.copy(
                status = RuntimeActionStatus.SUCCEEDED,
                commitStatus = RuntimeCommitStatus.COMMITTED,
                safeCode = "OK",
                retryable = false,
            ),
        )
        val encoded = ledger.encode()
        val restored = RuntimeObligationLedger.decode(encoded)
        assertEquals(ledger.snapshot(), restored.snapshot())
        val decoded = ObligationLedgerJson.decode(encoded)
        assertNotNull(decoded)
        assertEquals(ledger.snapshot(), decoded!!.obligations)
    }

    @Test
    fun unsupportedVersionIsRejected() {
        assertNull(ObligationLedgerJson.decode("""{"v":99,"secret":"abc","items":[]}"""))
        assertRestoreUnknown("""{"v":99,"secret":"abc","items":[]}""")
    }

    @Test
    fun legacyTsvIsRejected() {
        val tsv = "secret\tid\tstatus\nabc\t${validId()}\tSUCCEEDED"
        assertNull(ObligationLedgerJson.decode(tsv))
        assertRestoreUnknown(tsv)
    }

    @Test
    fun controlCharactersAreRejected() {
        val tab = """{"v":1,"secret":"abc","items":[]}""" + "\t"
        val cr = """{"v":1,"secret":"abc","items":[]}""" + "\r"
        val lf = """{"v":1,"secret":"abc","items":[]}""" + "\n"
        assertNull(ObligationLedgerJson.decode(tab))
        assertNull(ObligationLedgerJson.decode(cr))
        assertNull(ObligationLedgerJson.decode(lf))
        assertRestoreUnknown(tab)
    }

    @Test
    fun quoteInSecretIsRejected() {
        val encoded = """{"v":1,"secret":"ab\"c","items":[]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun oversizePayloadIsRejected() {
        val encoded = "x".repeat(MAX_LEDGER_ENCODED_CHARS + 1)
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun duplicateIdIsRejectedWithoutPartialMap() {
        val id = validId()
        val encoded =
            """{"v":1,"secret":"abc","items":[${itemJson(id)},${itemJson(id)}]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun invalidEnumIsRejected() {
        val encoded =
            """{"v":1,"secret":"abc","items":[${itemJson(validId(), status = "NOT_A_STATUS")}]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun invalidDigestIsRejected() {
        val encoded =
            """{"v":1,"secret":"abc","items":[${itemJson("not-a-digest")}]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun nonBooleanRetryableIsRejected() {
        val encoded =
            """{"v":1,"secret":"abc","items":[${itemJson(validId(), retryable = "\"yes\"")}]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun corruptRestoreYieldsUnknownObligation() {
        val ledger = RuntimeObligationLedger("keep")
        ledger.restore("not-json")
        val restored = ledger.snapshot().single()
        assertEquals(RESTORE_UNKNOWN_ID, restored.obligationId)
        assertEquals(RuntimeActionStatus.UNKNOWN, restored.status)
        assertEquals(RuntimeCommitStatus.UNKNOWN, restored.commitStatus)
        assertEquals(RESTORE_UNKNOWN_CODE, restored.safeCode)
        assertTrue(restored.isRequiredUnresolved())
    }

    @Test
    fun oneInvalidItemFailsClosedInsteadOfSilentDrop() {
        val good = itemJson(validId())
        val bad = itemJson("zzzz")
        val encoded = """{"v":1,"secret":"abc","items":[$good,$bad]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        val ledger = RuntimeObligationLedger.decode(encoded)
        assertEquals(listOf(RESTORE_UNKNOWN_ID), ledger.snapshot().map { it.obligationId })
    }

    @Test
    fun numericAndBooleanTokensAreRejected() {
        val id = validId()
        assertNull(ObligationLedgerJson.decode("""{"v":1,"secret":123,"items":[]}"""))
        assertNull(ObligationLedgerJson.decode("""{"v":1,"secret":true,"items":[]}"""))
        assertNull(
            ObligationLedgerJson.decode(
                """{"v":1,"secret":"abc","items":[{"id":1,"action":"remember_fact","status":"SUCCEEDED","commit":"COMMITTED","code":null,"retryable":false}]}""",
            ),
        )
        assertNull(
            ObligationLedgerJson.decode(
                """{"v":1,"secret":"abc","items":[${itemJson(id).replace("\"remember_fact\"", "false")}]}""",
            ),
        )
        assertRestoreUnknown("""{"v":1,"secret":123,"items":[]}""")
    }

    @Test
    fun backslashInTokenIsRejected() {
        val encoded = """{"v":1,"secret":"ab\\c","items":[]}"""
        assertNull(ObligationLedgerJson.decode(encoded))
        assertRestoreUnknown(encoded)
    }

    @Test
    fun encodeRejectsUnsafeOrOversizeInsteadOfAmbiguousLedger() {
        val unsafe = ObligationLedgerJson.encode("ab\"c", emptyList())
        val decodedUnsafe = ObligationLedgerJson.decode(unsafe)
        assertNotNull(decodedUnsafe)
        assertEquals(listOf(RESTORE_UNKNOWN_ID), decodedUnsafe!!.obligations.map { it.obligationId })
        assertTrue(decodedUnsafe.obligations.single().isRequiredUnresolved())

        val tooMany = (0..MAX_LEDGER_ITEMS).map { index ->
            RuntimeActionOutcome(
                obligationId = obligationDigest("oversize-$index"),
                actionName = "remember_fact",
                status = RuntimeActionStatus.SUCCEEDED,
                commitStatus = RuntimeCommitStatus.COMMITTED,
            )
        }
        val encoded = ObligationLedgerJson.encode("secret", tooMany)
        val decoded = ObligationLedgerJson.decode(encoded)!!
        assertEquals(1, decoded.obligations.size)
        assertEquals(RESTORE_UNKNOWN_ID, decoded.obligations.single().obligationId)
        assertTrue(decoded.obligations.single().isRequiredUnresolved())
        assertTrue(encoded.length <= MAX_LEDGER_ENCODED_CHARS)
    }

    private fun assertRestoreUnknown(encoded: String) {
        val ledger = RuntimeObligationLedger.decode(encoded)
        val restored = ledger.snapshot().single()
        assertEquals(RESTORE_UNKNOWN_ID, restored.obligationId)
        assertEquals(RuntimeActionStatus.UNKNOWN, restored.status)
    }

    private fun requiredDescriptor(
        providerId: String = "default",
        actionName: String = "remember_fact",
        effectKeyDigest: String? = null,
    ): RuntimeActionDescriptor =
        RuntimeActionDescriptor(
            providerId = providerId,
            actionName = actionName,
            effectKind = RuntimeEffectKind.MUTATING,
            impact = CompletionImpact.REQUIRED_ON_CALL,
            effectKeyDigest = effectKeyDigest,
        )

    private fun outcome(
        status: RuntimeActionStatus,
        commit: RuntimeCommitStatus,
    ): RuntimeActionOutcome =
        RuntimeActionOutcome(
            obligationId = validId(),
            actionName = "remember_fact",
            status = status,
            commitStatus = commit,
        )

    private fun validId(): String = obligationDigest("codec-item")

    private fun itemJson(
        id: String,
        status: String = "SUCCEEDED",
        retryable: String = "false",
    ): String =
        """{"id":"$id","action":"remember_fact","status":"$status","commit":"COMMITTED","code":null,"retryable":$retryable}"""
}
