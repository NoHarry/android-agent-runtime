package cc.noharry.agent.appserver.server

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi

/**
 * Plugins a library declares in its manifest, which the server loads without the host listing them: an entry
 *
 * ```xml
 * <application>
 *     <meta-data android:name="com.example.MyPlugin" android:value="cc.noharry.agent.appserver.plugin" />
 * </application>
 * ```
 *
 * names an [AppServerPlugin] class, a Kotlin `object` or a class with a public constructor that takes nothing. The
 * manifest merger brings the entry into every app that depends on the library, and the server's consumer rules keep
 * every plugin class with its name, so it is found in a release shrunk by R8 too. A host leaves one out with
 * [AppServerConfig.ignoredPlugins], and a plugin it lists itself with the same id is loaded as listed. One that cannot
 * be made is left out, with a warning in the log.
 */
@ExperimentalAppServerApi
object PluginDiscovery {
    /** The value of a manifest entry that names a plugin. */
    const val MANIFEST_VALUE: String = "cc.noharry.agent.appserver.plugin"

    /** The plugins the app's manifest declares that [config] neither lists nor ignores. */
    @cc.noharry.agent.appserver.InternalAppServerApi
    fun find(app: Context, config: AppServerConfig): List<AppServerPlugin> {
        val listed = config.plugins.mapTo(hashSetOf()) { it.id }
        return declared(metaData(app), app.classLoader).filter { it.id !in listed && it.id !in config.ignoredPlugins }
    }

    /** The plugins [metaData] declares, each id once, made with [loader]. */
    internal fun declared(metaData: Bundle?, loader: ClassLoader): List<AppServerPlugin> {
        val names = metaData?.keySet().orEmpty().filter { metaData?.getString(it) == MANIFEST_VALUE }.sorted()
        return names.mapNotNull { name -> load(name, loader) }.distinctBy { it.id }
    }

    private fun load(name: String, loader: ClassLoader): AppServerPlugin? =
        runCatching {
            val type = Class.forName(name, true, loader)
            val instance = type.declaredFields.firstOrNull { it.name == KOTLIN_OBJECT_FIELD && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.get(null)
                ?: type.getConstructor().newInstance()
            instance as AppServerPlugin
        }.onFailure { Log.w(TAG, "The manifest names $name as a plugin, which could not be made; it is left out.", it) }
            .getOrNull()

    private fun metaData(app: Context): Bundle? = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull()

    private const val KOTLIN_OBJECT_FIELD = "INSTANCE"
    private const val TAG = "AppServer"
}
