package cc.noharry.agent.appserver.models.deepseek

import android.content.Context
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.WebLookup
import cc.noharry.agent.appserver.models.ServiceCatalog
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceCheckPart
import cc.noharry.agent.appserver.protocol.ServiceCheckView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DeepSeek ([ProviderAdapter]): its service as the data file has it, and beyond chat its Search and Fetch APIs (on its
 * own host, or behind a relay a check found them behind), its system updates and `tool_addition` in a run's history on
 * its Anthropic-compatible endpoint (or a relay that passes them on), its balance, the check of a relay
 * ([DeepSeekProbes]), one key for every profile on its host, and its own pricing of models it does not list.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object DeepSeekAdapter : ProviderAdapter {
    /** DeepSeek's id among the services. */
    const val SERVICE_ID: String = "deepseek"

    override val services: List<ModelService> by lazy { listOf(ServiceCatalog.service(SERVICE_ID)) }

    /**
     * A model DeepSeek lists is priced at its list; one named as DeepSeek's that it does not list (an older version, a
     * name a relay uses) at its own model of that kind: every flash model at deepseek-flash, any other at the listed model
     * of the same last word (pro) whose name shares the longest start with it. Null for any other name.
     */
    override fun price(model: String): ModelPrice? {
        val service = services.single()
        service.model(model)?.price?.let { return it }
        val key = model.lowercase()
        if (!key.startsWith(SERVICE_ID) || '/' in key) return null
        val tier = if (key.split('-', '.').contains(FLASH)) FLASH else key.substringAfterLast('-')
        val best = service.models.filter { it.price != null && it.id.split('-').contains(tier) }
            .maxWithOrNull(compareBy({ it.id.commonPrefixWith(key).length }, { -it.id.length }))
            ?: return null
        return best.price?.copy(pricedAs = best.id)
    }

    override fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? = config.deepSeekToolsOf(context, profile)

    // Only where the APIs are: a relay's client waits for the trust store, which start-up loads off the main thread.
    override fun hasLookup(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean =
        config.deepSeekToolsUrl(context, profile) != null

    override fun nativeUpdates(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean =
        profile.usesDeepSeekNativeUpdates() || profile.endpoint == ProviderEndpoint.MESSAGES &&
            DeepSeekProbes.current(context, profile, config.effectiveApiKey(profile))?.passthrough?.passed == true

    override suspend fun balance(context: Context, service: ModelService, apiKey: String, relayUrl: String?): BalanceView {
        val balance = if (relayUrl == null) {
            fetchDeepSeekBalance(apiKey)
        } else {
            fetchDeepSeekBalance(apiKey, withContext(Dispatchers.IO) { deepSeekRelayClient(context) }, relayUrl)
        }
        return balance.getOrThrow().view()
    }

    override fun checked(context: Context, service: ModelService, profile: ProviderProfile, apiKey: String): ServiceCheckView =
        DeepSeekProbes.saved(context, profile.id).view(profile, apiKey)

    override suspend fun check(context: Context, service: ModelService, profile: ProviderProfile, apiKey: String): ServiceCheckView =
        DeepSeekProbes.check(context, profile, apiKey).view(profile, apiKey)

    override fun shareKeys(profiles: List<ProviderProfile>): List<ProviderProfile> = profiles.withSharedDeepSeekApiKeys()

    private const val FLASH = "flash"
}

/** DeepSeek's own Anthropic-compatible endpoint: system updates and `tool_addition` in history, `defer_loading` declarations. */
internal fun ProviderProfile.usesDeepSeekNativeUpdates(): Boolean = endpoint == ProviderEndpoint.MESSAGES && isDeepSeek()

/**
 * DeepSeek's endpoints (OpenAI-compatible and Anthropic-compatible) take one key: a profile on its host with no key of
 * its own takes the key of the first profile there that has one.
 */
internal fun List<ProviderProfile>.withSharedDeepSeekApiKeys(): List<ProviderProfile> {
    val key = firstOrNull { it.isDeepSeek() && it.apiKey.isNotBlank() }?.apiKey ?: return this
    return map { profile -> if (profile.isDeepSeek() && profile.apiKey.isBlank()) profile.copy(apiKey = key) else profile }
}

/**
 * [this] check (null: none made) as the screens see it for [profile] with [apiKey] as now filled in: its parts only when
 * it was made for that endpoint, address and key.
 */
internal fun DeepSeekProbe?.view(profile: ProviderProfile, apiKey: String): ServiceCheckView {
    val current = this?.takeIf { it.fingerprint == deepSeekProbeFingerprint(profile.endpoint, profile.baseUrl, apiKey) }
    return ServiceCheckView(
        checked = this != null,
        current = current != null,
        hasKey = apiKey.isNotBlank(),
        balance = current?.balance?.part(),
        lookup = current?.tools?.part(),
        nativeUpdates = current?.passthrough?.part(),
    )
}

private fun DeepSeekCheck.part() = ServiceCheckPart(passed, note, url)
