package cc.noharry.agent.androiduse

import android.content.Context
import android.provider.Settings

/**
 * Candidate switch for [AndroidUseAccessibilityService]. Not frozen.
 *
 * Enabling writes secure settings, so the host needs WRITE_SECURE_SETTINGS (granted to platform-signed
 * apps). It appends this service to the enabled list and removes only this entry, keeping every other
 * service enabled, such as the device's 可见即可说.
 */
object AndroidUseAccessibility {
    /** True while the service is bound and ready for tools. */
    fun isConnected(): Boolean = AndroidUseServiceHolder.service != null

    /** True when the service is in the system's enabled list. */
    fun isEnabled(context: Context): Boolean =
        serviceComponent(context) in enabledServices(Settings.Secure.getString(context.contentResolver, ENABLED_SERVICES))

    /** Adds the service to the enabled list; false when the host may not write secure settings. */
    fun enable(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val updated = withService(Settings.Secure.getString(resolver, ENABLED_SERVICES), serviceComponent(context))
        Settings.Secure.putString(resolver, ENABLED_SERVICES, updated) &&
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, ACCESSIBILITY_ON)
    }.getOrDefault(false)

    /** Removes only this service from the enabled list. */
    fun disable(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val updated = withoutService(Settings.Secure.getString(resolver, ENABLED_SERVICES), serviceComponent(context))
        Settings.Secure.putString(resolver, ENABLED_SERVICES, updated)
    }.getOrDefault(false)

    private fun serviceComponent(context: Context): String =
        "${context.packageName}/${AndroidUseAccessibilityService::class.java.name}"
}

/** Entries of an `enabled_accessibility_services` value, short `pkg/.Cls` forms expanded to `pkg/pkg.Cls`. */
internal fun enabledServices(value: String?): List<String> =
    value.orEmpty().split(SERVICE_SEPARATOR).map(String::trim).filter(String::isNotEmpty).map(::expandComponent)

internal fun withService(value: String?, component: String): String {
    val entries = value.orEmpty().split(SERVICE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
    return if (entries.any { expandComponent(it) == component }) entries.joinToString(SERVICE_SEPARATOR) else (entries + component).joinToString(SERVICE_SEPARATOR)
}

internal fun withoutService(value: String?, component: String): String =
    value.orEmpty().split(SERVICE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        .filterNot { expandComponent(it) == component }
        .joinToString(SERVICE_SEPARATOR)

private fun expandComponent(entry: String): String {
    val packageName = entry.substringBefore('/')
    val className = entry.substringAfter('/', missingDelimiterValue = "")
    return if (className.startsWith('.')) "$packageName/$packageName$className" else entry
}

/** A launcher entry: what the user sees and what to start. */
internal data class LauncherApp(val label: String, val packageName: String, val activityName: String)

/**
 * Candidates for the app the model named: exact labels or package first, then labels containing the name, then
 * labels the name contains. More than one candidate means the name is ambiguous and nothing should start.
 */
internal fun matchLauncherApps(apps: List<LauncherApp>, query: String): List<LauncherApp> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return emptyList()
    val tiers = listOf<(LauncherApp) -> Boolean>(
        { it.label.equals(wanted, ignoreCase = true) || it.packageName == wanted },
        { it.label.contains(wanted, ignoreCase = true) },
        { it.label.isNotBlank() && wanted.contains(it.label, ignoreCase = true) },
    )
    return tiers.asSequence().map { tier -> apps.filter(tier).distinctBy { it.packageName } }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

private const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
private const val ACCESSIBILITY_ON = "1"
private const val SERVICE_SEPARATOR = ":"
