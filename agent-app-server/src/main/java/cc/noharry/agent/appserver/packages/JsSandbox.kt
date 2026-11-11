@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.exec.js.ZiplineExecEngine
import cc.noharry.agent.spi.ExecEngine

/**
 * The JavaScript sandbox scripts run in (plugin packages' tools written as scripts, `exec_js` of the `scripts`
 * built-in): agent-exec-js, QuickJS through Zipline. The server is compiled against it but does not carry it, since its
 * native code is about 4 MB per app: a host that wants scripts adds `cc.noharry:agent-exec-js` to its dependencies, and
 * without it [available] is false, a package with script tools is refused at install and a script tool's call fails
 * saying [MISSING].
 */
internal object JsSandbox {
    /** What a script tool's call, a package's install and the server's start say when the sandbox is not in the app. */
    const val MISSING = "scripts need agent-exec-js, which this app does not include"

    /**
     * Whether agent-exec-js is in the app: its engine's class is found by name, which its consumer rules keep in a
     * release build. (A class literal would not do: R8 drops one whose value is not used, check and all.)
     */
    val available: Boolean by lazy {
        try {
            Class.forName(ENGINE_CLASS, false, JsSandbox::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** A new engine with these limits ([ZiplineExecEngine]); only when [available]. */
    fun engine(maxToolCalls: Int, memoryBytes: Long?): ExecEngine = ZiplineExecEngine(maxToolCalls, memoryBytes)

    /** An engine for `exec_js`, with the engine's own limits; only when [available]. */
    fun execEngine(): ExecEngine = ZiplineExecEngine()

    private const val ENGINE_CLASS = "cc.noharry.agent.exec.js.ZiplineExecEngine"
}
