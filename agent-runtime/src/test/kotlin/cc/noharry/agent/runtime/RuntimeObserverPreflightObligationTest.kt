@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeCommitStatus
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.RuntimeObserverActionPreflight
import cc.noharry.agent.kernel.api.RuntimeTurnInput
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.api.TurnObservationReceipt
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import cc.noharry.agent.kernel.core.RuntimeContextHooks
import cc.noharry.agent.kernel.core.RuntimeObligationLedger
import cc.noharry.agent.kernel.core.normalizeTransportResult
import cc.noharry.agent.kernel.core.isRequiredUnresolved
import cc.noharry.agent.kernel.core.isSatisfied

class RuntimeObserverPreflightObligationTest {
    @Test
    fun nestedBusinessRefusalCannotSatisfyARequiredAction() {
        val raw = """{"committed":true,"result":{"isError":true,"structuredContent":{"status":{"name":"INVALID_ARGUMENT"}}}}"""
        val outcome = normalizeTransportResult("id", "route", true, raw)
        assertEquals(cc.noharry.agent.kernel.api.RuntimeActionStatus.FAILED, outcome.status)
        assertEquals("INVALID_ARGUMENT", outcome.safeCode)
    }

    @Test
    fun preflightDeclarationHappensBeforeOnTurnInput() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        val observer = ScriptedObserver(
            onPreflight = { events += "preflight"; defaultPreflight() },
            onInput = {
                events += "onTurnInput"
                declaredBeforeInput = ledger.snapshot().singleOrNull()
                    ?.takeIf { it.status == cc.noharry.agent.kernel.api.RuntimeActionStatus.DECLARED }
                committedObservation()
            },
        )
        notify(observer, ledger)
        assertEquals(listOf("preflight", "onTurnInput"), observer.events)
        assertEquals(1, ledger.snapshot().size)
        assertEquals(observer.declaredBeforeInput?.obligationId, ledger.snapshot().single().obligationId)
        assertTrue(ledger.snapshot().single().isSatisfied())
    }

    @Test
    fun committedMatchingReceiptCanSatisfyDeclaredObligation() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(ScriptedObserver(onInput = { committedObservation() }), ledger)
        assertCanPass(ledger)
    }

    @Test
    fun timeoutLeavesPreflightUnresolvedAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(
            ScriptedObserver(
                onInput = {
                    delay(RuntimeContextHooks.EXPLICIT_MUTATION_TIMEOUT_MS + 250)
                    committedObservation()
                },
            ),
            ledger,
        )
        assertCannotPass(ledger)
        assertEquals(cc.noharry.agent.kernel.api.RuntimeActionStatus.DECLARED, ledger.snapshot().single().status)
    }

    @Test
    fun nonCancellationThrowLeavesPreflightUnresolvedAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(ScriptedObserver(onInput = { error("observer boom") }), ledger)
        assertCannotPass(ledger)
    }

    @Test
    fun cancellationExceptionPropagatesAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        try {
            notify(ScriptedObserver(onInput = { throw CancellationException("observer cancelled") }), ledger)
            fail("expected CancellationException")
        } catch (cancelled: CancellationException) {
            assertEquals("observer cancelled", cancelled.message)
        }
        assertCannotPass(ledger)
    }

    @Test
    fun timeoutCancellationDoesNotCancelParentAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(
            ScriptedObserver(
                onInput = {
                    withTimeout(1) { delay(50) }
                    committedObservation()
                },
            ),
            ledger,
        )
        assertCannotPass(ledger)
    }

    @Test
    fun unhandledReceiptLeavesPreflightUnresolvedAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(ScriptedObserver(onInput = { TurnInputObservation.noop(id) }), ledger)
        assertCannotPass(ledger)
    }

    @Test
    fun notCommittedReceiptLeavesPreflightUnresolvedAndCannotPass() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(
            ScriptedObserver(
                onInput = {
                    TurnInputObservation(
                        observerId = id,
                        handled = true,
                        writeCommitted = false,
                        safeCode = "NOT_COMMITTED",
                        effectKind = RuntimeEffectKind.MUTATING,
                        completionImpact = CompletionImpact.REQUIRED_ON_CALL,
                        commitStatus = RuntimeCommitStatus.NOT_COMMITTED,
                        effectKeyDigest = EFFECT_KEY,
                    )
                },
            ),
            ledger,
        )
        assertCannotPass(ledger)
        assertFalse(ledger.snapshot().single().isSatisfied())
    }

    @Test
    fun identityMismatchDoesNotSatisfyDeclaredObligation() = runBlocking {
        val ledger = RuntimeObligationLedger("secret")
        notify(
            ScriptedObserver(
                onInput = {
                    TurnInputObservation(
                        observerId = id,
                        handled = true,
                        writeCommitted = true,
                        effectKind = RuntimeEffectKind.MUTATING,
                        completionImpact = CompletionImpact.REQUIRED_ON_CALL,
                        commitStatus = RuntimeCommitStatus.COMMITTED,
                        effectKeyDigest = "other-effect",
                    )
                },
            ),
            ledger,
        )
        assertEquals(1, ledger.snapshot().size)
        assertCannotPass(ledger)
    }

    @Test
    fun malformedAndMissingEvidenceCannotSatisfyTransportResult() {
        assertUnresolved("""{"ok":true}""")
        assertUnresolved("not-json")
        listOf(
            """{"ok":false,"committed":true}""",
            """{"ok":true,"isError":true,"committed":true}""",
            """{"ok":true,"committed":false}""",
            """{"ok":true,"committed":true,"code":"TIMEOUT"}""",
            """{"ok":true,"committed":true,"code":"CANCELLED"}""",
            """{"ok":true,"committed":true,"code":"DENIED"}""",
            """{"ok":true,"committed":true,"code":"WRITE_FAILED"}""",
            """{"ok":true,"noop":true}""",
        ).forEach(::assertUnresolved)
        listOf(
            """{"ok":true,"committed":true}""",
            """{"ok":true,"committed":true,"noop":true}""",
            """{"ok":true,"writeCommitted":true,"noop":true}""",
            """{"ok":true,"commitStatus":"NO_CHANGE_REQUIRED"}""",
            """{"ok":true,"commit":"NO_CHANGE_REQUIRED"}""",
        ).forEach { payload ->
            assertTrue(payload, normalizeTransportResult("id", "remember_fact", true, payload).isSatisfied())
        }
        assertEquals(
            RuntimeCommitStatus.NO_CHANGE_REQUIRED,
            normalizeTransportResult(
                "id",
                "remember_fact",
                true,
                """{"ok":true,"committed":true,"noop":true,"commitStatus":"NO_CHANGE_REQUIRED"}""",
            ).commitStatus,
        )
    }

    private fun assertUnresolved(resultJson: String) {
        val outcome = normalizeTransportResult("id", "remember_fact", true, resultJson)
        assertFalse(resultJson, outcome.isSatisfied())
    }

    private suspend fun notify(observer: RuntimeTurnObserver, ledger: RuntimeObligationLedger) {
        RuntimeContextHooks.notifyTurnInput(
            turnObservers = listOf(observer),
            request = AgentRequest("req-1", "sess-1", "remember this"),
            runId = "run-1",
            referenceTimeEpochMillis = 1_000L,
            obligationLedger = ledger,
        )
    }

    private fun assertCannotPass(ledger: RuntimeObligationLedger) {
        val summary = ledger.summarize(RuntimeCompletionOutcome.SUCCEEDED)
        assertTrue(summary.requiredUnresolved >= 1)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(RuntimeRunStatus.COMPLETED, summary))
        assertTrue(ledger.snapshot().any { it.isRequiredUnresolved() })
    }

    private fun assertCanPass(ledger: RuntimeObligationLedger) {
        val summary = ledger.summarize(RuntimeCompletionOutcome.SUCCEEDED)
        assertEquals(0, summary.requiredUnresolved)
        assertTrue(RuntimeCompletionSummary.firstPartyPass(RuntimeRunStatus.COMPLETED, summary))
        assertTrue(ledger.snapshot().single().isSatisfied())
    }

    private class ScriptedObserver(
        private val onPreflight: ScriptedObserver.() -> RuntimeObserverActionPreflight? = { defaultPreflight() },
        private val onInput: suspend ScriptedObserver.() -> TurnInputObservation,
    ) : RuntimeTurnObserver {
        override val id: String = OBSERVER_ID
        val events = mutableListOf<String>()
        var declaredBeforeInput: cc.noharry.agent.kernel.api.RuntimeActionOutcome? = null

        override fun preflightTurnInput(input: RuntimeTurnInput): RuntimeObserverActionPreflight? = onPreflight()

        override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation = onInput()

        override suspend fun onTurnFinished(turn: cc.noharry.agent.kernel.api.RuntimeCompletedTurn): TurnObservationReceipt =
            TurnObservationReceipt(id, enqueued = false)

        fun defaultPreflight(): RuntimeObserverActionPreflight =
            RuntimeObserverActionPreflight(
                observerId = id,
                actionName = "explicit-mutation",
                effectKind = RuntimeEffectKind.MUTATING,
                completionImpact = CompletionImpact.REQUIRED_ON_CALL,
                effectKeyDigest = EFFECT_KEY,
                argumentDigest = ARGUMENT_DIGEST,
            )

        fun committedObservation(): TurnInputObservation =
            TurnInputObservation(
                observerId = id,
                handled = true,
                writeCommitted = true,
                effectKind = RuntimeEffectKind.MUTATING,
                completionImpact = CompletionImpact.REQUIRED_ON_CALL,
                commitStatus = RuntimeCommitStatus.COMMITTED,
                effectKeyDigest = EFFECT_KEY,
                digest = ARGUMENT_DIGEST,
            )
    }

    private companion object {
        const val OBSERVER_ID = "required-observer"
        const val EFFECT_KEY = "declared-effect"
        const val ARGUMENT_DIGEST = "declared-args"
    }
}
