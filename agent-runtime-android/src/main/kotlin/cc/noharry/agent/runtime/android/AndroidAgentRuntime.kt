package cc.noharry.agent.runtime.android

import android.content.Context
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.SessionHistory

/**
 * An [AgentRuntimeBuilder] set up for an Android app: the agent's sandboxed workspace in the app's files
 * (`filesDir/agent-runtime/workspace`), sessions in SQLite there, the durable event log in no-backup storage, and a
 * conversation history kept in memory by the runtime it builds: keep that runtime and later runs in a session see the
 * earlier ones. Add a model client and tools, then build:
 *
 * ```
 * val runtime = AndroidAgentRuntime.builder(context)
 *     .modelClient(OpenAiModelClient({ apiKey }, model = "gpt-4o-mini", stream = true))
 *     .toolProvider(myTools)
 *     .build()
 * ```
 * Candidate API, not frozen.
 */
object AndroidAgentRuntime {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    fun builder(context: Context): AgentRuntimeBuilder {
        val app = context.applicationContext
        return AgentRuntimeBuilder.create()
            .workspaceRoot(appStorage(app.filesDir, "$ROOT/workspace"))
            .androidSqliteSessions()
            .sessionEventLogRoot(appStorage(app.noBackupFilesDir, "$ROOT/event-log"))
            .sessionHistory(SessionHistory())
    }

    private const val ROOT = "agent-runtime"
}
