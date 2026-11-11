package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ProviderProfileOption

data class ProviderProfile(
    val id: String,
    val label: String,
    val provider: ModelProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val endpoint: ProviderEndpoint = ProviderEndpoint.defaultFor(provider),
    val variant: String = "",
    val streamEnabled: Boolean? = null,
    val serverSideSearch: Boolean = true,
    val searchCapability: String = "",
) {
    val displayLabel: String = label.ifBlank { provider.label }

    internal fun withModel(model: String): ProviderProfile =
        copy(model = model.trim().ifBlank { this.model })

    internal fun toOption(activeProfileId: String): ProviderProfileOption =
        ProviderProfileOption(
            id = id,
            label = displayLabel,
            provider = provider,
            endpoint = endpoint,
            model = model,
            selected = id == activeProfileId,
            canDelete = id.isManualProfileId(),
        )
}

internal data class ProviderSelection(
    val defaultProfileId: String,
    val activeProfile: ProviderProfile,
)
