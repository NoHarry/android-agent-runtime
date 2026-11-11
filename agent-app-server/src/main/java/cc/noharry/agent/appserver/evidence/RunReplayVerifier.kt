@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

/** The legacy result name is retained for session compatibility; it reports only event and answer checks. */
internal data class RunReplayResult(
    val passed: Boolean,
    val summary: String,
    val failures: List<String>,
    val warnings: List<String>,
)

/**
 * Public event lifecycle and host answer checks, made after every run in memory (kept as a file only with diagnostics).
 * A run fails it when its events do not add up (a public tool call never finished, a finish without a start), when the
 * host's [cc.noharry.agent.appserver.runtime.ReplayCheck] says the request needed a tool that acts and none was called
 * or finds the answer wrong, and when it did not end as done: a verified success, a stop to ask the person (the kernel's
 * `ask`, or a partial or blocked reply that asks), or work handed to subagents. A partial or blocked answer that asks
 * nothing fails it, as a bench counts such a run; the screens show that run's own outcome (partial, blocked), and a
 * failed check marks only a run that otherwise completed. This does not compare an independent diagnostic replay.
 */
internal object RunReplayVerifier {
    fun verify(evidence: RunEvidence): RunReplayResult {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        addFailures(evidence, failures)
        addWarnings(evidence, warnings)
        // The host's own checks of an answer that passed otherwise ([cc.noharry.agent.appserver.runtime.ReplayCheck]).
        if (evidence.firstPartyPassed()) {
            val problems = AppHost.replayCheck.answerProblems(evidence.inputPreview, evidence.finalResponse.trim())
            failures += problems.failures
            warnings += problems.warnings
        }
        val passed = failures.isEmpty()
        val summary = "eventChecks=${if (passed) "PASS" else "FAIL"} source=public_thread_events " +
            "final=${evidence.finalStatus ?: "missing"} " +
            "public=${evidence.counts.publicFinished}/${evidence.counts.publicStarted} " +
            "business=${evidence.counts.meaningfulBusinessTools} actionable=${evidence.requiresBusinessAction} " +
            "toolFailures=${evidence.counts.failedTools}"
        return RunReplayResult(passed, summary, failures, warnings)
    }

    private fun addFailures(evidence: RunEvidence, failures: MutableList<String>) {
        if (!evidence.firstPartyPassed() && !evidence.handedToSubagents() && !evidence.stoppedToAsk()) {
            failures += "final result is not a first-party pass " +
                "(status=${evidence.finalStatus ?: "missing"} " +
                "completion=${evidence.finalCompletionOutcome ?: "missing"})"
        }
        if (evidence.counts.pendingPublicTools > 0) {
            failures += "public tool calls still pending: ${pendingPublicNames(evidence)}"
        }
        if (evidence.counts.orphanPublicFinishes > 0) {
            failures += "public tool finishes without starts: ${orphanPublicNames(evidence)}"
        }
        if (evidence.requiresBusinessAction && evidence.counts.meaningfulBusinessTools == 0) {
            failures += "actionable request completed without a meaningful business tool"
        }
    }

    private fun addWarnings(evidence: RunEvidence, warnings: MutableList<String>) {
        if (evidence.counts.failedTools > 0) {
            warnings += "tool failures observed: ${failedToolNames(evidence)}"
        }
    }
}

/**
 * A completed run that answered first and left the work to its subagents: the model rightly calls that partial, but
 * the request was handled, and the run is not reported as not done while its subagents' lookups are under way.
 */
internal fun RunEvidence.handedToSubagents(): Boolean =
    finalStatus == RuntimeRunStatus.COMPLETED.name &&
        finalCompletionOutcome == RuntimeCompletionOutcome.PARTIAL.name &&
        tools.any { it.toolName == SPAWN_AGENT_TOOL }

private const val SPAWN_AGENT_TOOL = "spawn_agent"

/**
 * A run that stopped to ask the person (the kernel's `ask`) ended as it should: the question is its reply. It calls no
 * `finish`, so its completion stays UNKNOWN; its last tool is the ask.
 */
internal fun RunEvidence.stoppedToAsk(): Boolean =
    finalStatus == RuntimeRunStatus.WAITING_USER_INPUT.name || finalStatus == cc.noharry.agent.TurnStatus.WAITING_FOR_ANSWER.name ||
        tools.lastOrNull()?.toolName == ASK_TOOL

private const val ASK_TOOL = "ask"

internal fun RunEvidence.firstPartyPassed(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus.orEmpty()) }.getOrNull()
        ?: return false
    val outcome = runCatching {
        RuntimeCompletionOutcome.valueOf(finalCompletionOutcome ?: RuntimeCompletionOutcome.UNKNOWN.name)
    }.getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    return RuntimeCompletionSummary.firstPartyPass(
        status,
        RuntimeCompletionSummary(outcome = outcome),
    )
}

private fun pendingPublicNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.publicStarted && !it.publicFinished }.toolNames()

private fun orphanPublicNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.publicFinished && !it.publicStarted }.toolNames()

private fun failedToolNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.status in FAILED_TOOL_STATUSES }.toolNames()

private fun List<RunToolEvidence>.toolNames(): String =
    joinToString("|") { "${it.sequence}:${it.toolName}" }
