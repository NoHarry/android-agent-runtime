package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.normalizeProviderModels
import cc.noharry.agent.appserver.runtime.AppHost
import java.security.MessageDigest
import org.json.JSONArray

/** Saved model candidates; selecting the active model remains a separate composer action. */
internal fun loadProviderModels(context: Context, profile: ProviderEditState): List<String> {
    // The on-device profile offers the models the app can download, each picked like any other model.
    if (AppHost.models.owns(profile.profileId)) return AppHost.models.models(profile.profileId)
    val raw = context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE)
        .getString(providerModelsKey(profile), null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        normalizeProviderModels((0 until array.length()).map { array.getString(it) })
    }.getOrDefault(emptyList())
}

internal fun saveProviderModelList(context: Context, profile: ProviderEditState, models: List<String>) {
    context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE).edit()
        .putString(providerModelsKey(profile), JSONArray(normalizeProviderModels(models)).toString()).apply()
}

internal fun deleteProviderModels(context: Context, profileId: String) {
    val prefs = context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE)
    val prefix = "${profileId.preferenceKey()}:"
    prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply()
}

private fun providerModelsKey(profile: ProviderEditState): String {
    val connection = listOf(profile.provider.wireName, profile.endpoint.wireName, profile.baseUrl.trim()).joinToString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256").digest(connection.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${profile.profileId.preferenceKey()}:$digest"
}

private const val PROVIDER_MODELS_PREFS = "app_server_provider_models"
