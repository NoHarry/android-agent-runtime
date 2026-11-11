package cc.noharry.agent.appserver.models

import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.models.deepseek.DeepSeekAdapter
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext

/**
 * The model services the server knows when this module is on the classpath: DeepSeek, OpenAI, Anthropic, Google Gemini,
 * xAI, OpenRouter, Groq, Moonshot (Kimi), Zhipu (GLM), Alibaba Cloud Model Studio (Qwen), Volcengine Ark (Doubao),
 * MiniMax and SiliconFlow, each with its adapter ([ProviderAdapter]): how it is reached, its models with what is known
 * of them and their prices, how its failures read, and for DeepSeek its extras ([DeepSeekAdapter]). The module's
 * manifest declares it, so a host that depends on the module gets it without listing it
 * ([cc.noharry.agent.appserver.server.PluginDiscovery]); a person turns it off and on as any plugin
 * (`plugins/set {"id":"models",...}`), and a host leaves it out with `AppServerConfig.ignoredPlugins = setOf("models")`.
 */
@ExperimentalAppServerApi
object ModelsPlugin : AppServerPlugin {
    override val id: String = "models"

    override fun apply(ctx: PluginContext) {
        adapters().forEach(ctx::providerAdapter)
    }

    /** One adapter per service of the data file, DeepSeek's with its extras. */
    internal fun adapters(): List<ProviderAdapter> =
        ServiceCatalog.services.map { service -> if (service.id == DeepSeekAdapter.SERVICE_ID) DeepSeekAdapter else ServiceAdapter(service) }
}

/** A service known by its data alone: the defaults of [ProviderAdapter] read it. */
internal class ServiceAdapter(service: ModelService) : ProviderAdapter {
    override val services: List<ModelService> = listOf(service)

    override fun toString(): String = "ServiceAdapter(${services.single().id})"
}
