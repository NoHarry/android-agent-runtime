package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.normalizeProviderModels

internal class ModelOptionsController(private val onChanged: () -> Unit) {
    private val optionsByProfile = linkedMapOf<String, List<String>>()

    fun options(profile: ProviderProfile, selectedModel: String): List<String> =
        normalizeProviderModels(listOf(selectedModel) + optionsByProfile[profile.id].orEmpty())

    fun refresh(context: Context, profile: ProviderProfile, config: ProviderConfig) {
        optionsByProfile[profile.id] = loadProviderModels(context, config.editState(profile))
        onChanged()
    }
}
