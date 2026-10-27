package cc.noharry.agent.runtime.reliability
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.state.isTerminalRunStatus
import java.io.File

internal data class StoredRunRecord(
    val directory: File,
    val runId: String,
    val status: RuntimeRunStatus?,
    val updatedAtEpochMillis: Long,
) {
    val canDelete: Boolean = status?.isTerminalRunStatus() == true
}
