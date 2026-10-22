package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode

internal class RuntimeVfsReadGuard {
    private val reads = linkedMapOf<String, ReadState>()

    fun record(path: String, revision: String?, fullRead: Boolean) {
        reads[path] = if (fullRead) {
            ReadState.Full(revision)
        } else {
            ReadState.Partial
        }
    }

    fun revisionForWrite(path: String): ReadGuardDecision =
        when (val state = reads[path]) {
            is ReadState.Full -> ReadGuardDecision.Allowed(state.revision)
            ReadState.Partial -> ReadGuardDecision.Denied(
                RuntimeErrorCode.WORKSPACE_FULL_READ_REQUIRED.wireCode,
                "Read the full file before editing or overwriting it.",
            )
            null -> ReadGuardDecision.Denied(
                RuntimeErrorCode.WORKSPACE_READ_REQUIRED.wireCode,
                "Read the file before editing or overwriting it.",
            )
        }
}

internal sealed interface ReadGuardDecision {
    data class Allowed(val expectedRevision: String?) : ReadGuardDecision

    data class Denied(val code: String, val message: String) : ReadGuardDecision
}

private sealed interface ReadState {
    data class Full(val revision: String?) : ReadState

    data object Partial : ReadState
}
