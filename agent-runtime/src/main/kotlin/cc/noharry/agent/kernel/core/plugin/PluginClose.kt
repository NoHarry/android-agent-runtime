package cc.noharry.agent.kernel.core.plugin
internal fun closeSafely(resource: AutoCloseable) {
    try {
        resource.close()
    } catch (_: Throwable) {
        // Host/plugin cleanup must never escape the runtime close boundary.
    }
}

internal fun closeSafelyValue(value: Any?) {
    if (value is AutoCloseable) {
        closeSafely(value)
    }
}
