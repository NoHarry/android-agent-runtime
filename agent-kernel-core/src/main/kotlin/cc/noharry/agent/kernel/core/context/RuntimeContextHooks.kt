@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.context
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.RuntimeContextContribution
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeObserverActionPreflight
import cc.noharry.agent.kernel.api.RuntimeTurnInput
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.core.loop.RuntimeActionDescriptor
import cc.noharry.agent.kernel.core.loop.RuntimeObligationLedger
import cc.noharry.agent.kernel.core.state.toRequiredOutcome
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pre-model turn observers and aggregate-deadline context providers.
 * Extracted from [NativeToolLoop] to keep hot-path file under quality size limits.
 */
object RuntimeContextHooks {
    /** Aggregate deadline shared by all context providers on the hot path. */
    const val CONTEXT_AGGREGATE_TIMEOUT_MS: Long = 20L
    /**
     * Runtime-owned per-observer budget. This is the single deadline for pre-model
     * observer invocation; observers must not install an equal competing timeout.
     */
    const val EXPLICIT_MUTATION_TIMEOUT_MS: Long = 2_000L

    suspend fun notifyTurnInput(
        turnObservers: List<RuntimeTurnObserver>,
        request: AgentRequest,
        runId: String,
        referenceTimeEpochMillis: Long,
        inputOverride: String? = null,
        runtimeInstanceId: String? = null,
        obligationLedger: RuntimeObligationLedger? = null,
    ) {
        if (turnObservers.isEmpty()) return
        val deadlineEpochMillis = referenceTimeEpochMillis + EXPLICIT_MUTATION_TIMEOUT_MS
        val input =
            RuntimeTurnInput(
                runId = runId,
                sessionId = request.sessionId,
                requestId = request.requestId,
                scene = request.scene,
                input = inputOverride ?: request.input,
                referenceTimeEpochMillis = referenceTimeEpochMillis,
                deadlineEpochMillis = deadlineEpochMillis,
                runtimeInstanceId = runtimeInstanceId,
            )
        for (observer in turnObservers) {
            val preflight = observerPreflight(observer, input)
            val declared =
                if (preflight != null && obligationLedger != null) {
                    declareObserverAction(obligationLedger, runId, preflight)
                } else {
                    null
                }
            val observation = try {
                withTimeoutOrNull(EXPLICIT_MUTATION_TIMEOUT_MS) {
                    observer.onTurnInput(input)
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                if (ex is TimeoutCancellationException) {
                    null
                } else {
                    throw ex
                }
            } catch (_: Exception) {
                null
            }
            if (observation != null && obligationLedger != null && declared != null) {
                recordObserverObligation(obligationLedger, declared, observation)
            }
        }
    }

    private fun observerPreflight(
        observer: RuntimeTurnObserver,
        input: RuntimeTurnInput,
    ): RuntimeObserverActionPreflight? =
        try {
            observer.preflightTurnInput(input)
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (_: Exception) {
            null
        }

    private fun declareObserverAction(
        ledger: RuntimeObligationLedger,
        runId: String,
        preflight: RuntimeObserverActionPreflight,
    ): DeclaredObserverObligation? {
        val descriptor = RuntimeActionDescriptor(
            providerId = preflight.observerId,
            actionName = preflight.actionName,
            effectKind = preflight.effectKind,
            impact = preflight.completionImpact,
            effectKeyDigest = preflight.effectKeyDigest,
        )
        val declared = ledger.declareRequiredMutating(runId, descriptor, preflight.argumentDigest.orEmpty())
            ?: return null
        return DeclaredObserverObligation(
            obligationId = declared.obligationId,
            observerId = preflight.observerId,
            actionName = preflight.actionName,
            effectKeyDigest = preflight.effectKeyDigest,
            argumentDigest = preflight.argumentDigest,
        )
    }

    private fun recordObserverObligation(
        ledger: RuntimeObligationLedger,
        declared: DeclaredObserverObligation,
        observation: TurnInputObservation,
    ) {
        if (!observationMatchesDeclaration(declared, observation)) return
        val outcome = observation.toRequiredOutcome(declared.obligationId, declared.actionName) ?: return
        ledger.recordOutcome(outcome)
    }

    private fun observationMatchesDeclaration(
        declared: DeclaredObserverObligation,
        observation: TurnInputObservation,
    ): Boolean {
        if (observation.observerId != declared.observerId) return false
        if (declared.effectKeyDigest != null || observation.effectKeyDigest != null) {
            return declared.effectKeyDigest == observation.effectKeyDigest
        }
        return declared.argumentDigest.orEmpty() == observation.digest.orEmpty()
    }

    private data class DeclaredObserverObligation(
        val obligationId: String,
        val observerId: String,
        val actionName: String,
        val effectKeyDigest: String?,
        val argumentDigest: String?,
    )

    /**
     * Collect context under one aggregate monotonic deadline.
     * Each provider only receives remaining budget; exhausted deadline skips the rest (fail-open).
     */
    suspend fun collectContextSystemSuffix(
        contextProviders: List<RuntimeContextProvider>,
        request: AgentRequest,
        runId: String,
        referenceTimeEpochMillis: Long,
        inputOverride: String? = null,
        runtimeInstanceId: String? = null,
        monotonicNanos: () -> Long = System::nanoTime,
    ): String {
        if (contextProviders.isEmpty()) return ""
        val contributions = mutableListOf<RuntimeContextContribution>()
        val seenProviderIds = mutableSetOf<String>()
        val deadlineNanos = monotonicNanos() + CONTEXT_AGGREGATE_TIMEOUT_MS * 1_000_000L
        val wallDeadline = referenceTimeEpochMillis + CONTEXT_AGGREGATE_TIMEOUT_MS
        for (provider in contextProviders) {
            if (!seenProviderIds.add(provider.id)) continue
            val remainingMs = ((deadlineNanos - monotonicNanos()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            val contextRequest =
                RuntimeContextRequest(
                    runId = runId,
                    sessionId = request.sessionId,
                    requestId = request.requestId,
                    scene = request.scene,
                    input = inputOverride ?: request.input,
                    referenceTimeEpochMillis = referenceTimeEpochMillis,
                    deadlineEpochMillis = wallDeadline,
                    currentStateKeys = request.currentStateKeys,
                    runtimeInstanceId = runtimeInstanceId,
                )
            // Catch provider failures inside the timeout scope so a throwing provider cannot
            // cancel the parent collector (structured concurrency) and skip remaining providers.
            val contribution =
                try {
                    withTimeoutOrNull(remainingMs) {
                        try {
                            provider.provide(contextRequest)
                        } catch (ex: kotlinx.coroutines.CancellationException) {
                            throw ex
                        } catch (_: Exception) {
                            null
                        }
                    }
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    if (ex is TimeoutCancellationException) {
                        null
                    } else {
                        throw ex
                    }
                } catch (_: Exception) {
                    null
                }
            if (contribution != null && contribution.providerId == provider.id) {
                contributions += contribution
            }
        }
        return RuntimeContextRenderer.renderSystemSuffix(contributions)
    }
}
