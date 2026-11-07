package cc.noharry.agent.spi

import cc.noharry.agent.runtime.run.DEFAULT_HOOK_TIMEOUT_MILLIS

/** Internal hook registration with a matcher, per-hook timeout, and failure policy. */
internal data class RuntimeHookRegistration(
    val hook: RuntimeHook,
    val matcher: ToolMatcher = ToolMatcher.All,
    val timeoutMillis: Long = DEFAULT_HOOK_TIMEOUT_MILLIS,
    val failurePolicy: HookFailurePolicy = HookFailurePolicy.FAIL_CLOSED,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive." }
    }
}
