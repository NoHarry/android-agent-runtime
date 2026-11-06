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
 */
object AndroidAgentRuntime {
    /**
     * [name] keeps a runtime's workspace, sessions, event log and lessons apart from another one's in the same app
     * (`agent-runtime/runtimes/<name>/…`, outside the unnamed runtime's workspace); without one they are the app's
     * single runtime's (`agent-runtime/…`). Letters, digits, `-` and `_`.
     */
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @JvmOverloads
    fun builder(context: Context, name: String? = null): AgentRuntimeBuilder {
        require(name == null || NAME.matches(name)) { "A runtime's name is letters, digits, - and _: $name" }
        val app = context.applicationContext
        val root = name?.let { "$ROOT/runtimes/$it" } ?: ROOT
        return AgentRuntimeBuilder.create()
            .workspaceRoot(appStorage(app.filesDir, "$root/workspace"))
            .androidSqliteSessions()
            .sessionEventLogRoot(appStorage(app.noBackupFilesDir, "$root/event-log"))
            .sessionHistory(SessionHistory())
    }

    private const val ROOT = "agent-runtime"
    private val NAME = Regex("[A-Za-z0-9_-]{1,64}")
}
