package cc.noharry.agent.runtime.android

import android.content.Context
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.core.state.SESSION_STORE_DB_FILE
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin
import java.io.File

/**
 * Keeps sessions, runs, messages and usage in an SQLite database under the builder's workspace root
 * (`runtime-session-store.sqlite`). Candidate API, not frozen.
 */
@ExperimentalAgentRuntimeApi
fun AgentRuntimeBuilder.androidSqliteSessions(): AgentRuntimeBuilder =
    sessionStore { workspaceRoot -> AndroidSqliteSessionStore(File(workspaceRoot, SESSION_STORE_DB_FILE)) }

/** The durable session event log in the app's no-backup storage. Candidate API, not frozen. */
@ExperimentalAgentRuntimeApi
fun DurableSessionEventLogPlugin.Companion.appPrivate(context: Context): DurableSessionEventLogPlugin =
    DurableSessionEventLogPlugin.forRoot(appStorage(context.noBackupFilesDir, DurableSessionEventLogPlugin.ROOT_DIRECTORY))

/**
 * [name] under an app directory, as its canonical path. An app's directories sit under `/data/user/0`, a link to
 * `/data/data`, and the event log refuses a path that goes through a link.
 */
internal fun appStorage(dir: File, name: String): File = File(dir, name).let { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }
