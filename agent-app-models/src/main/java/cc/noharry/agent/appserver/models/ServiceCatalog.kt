package cc.noharry.agent.appserver.models

import cc.noharry.agent.appserver.model.ErrorRule
import cc.noharry.agent.appserver.model.ModelFailureKind
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.RetryPolicy
import cc.noharry.agent.appserver.model.ServiceEndpoint
import cc.noharry.agent.appserver.model.ServiceModel
import cc.noharry.agent.appserver.model.UsageFields
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model services this module knows, read once from its data file (`services.json` beside this class, a Java
 * resource of the artifact): for each, where it is reached, its models with their facts and prices, how its failures
 * read, and the official pages each value was read from, with the day.
 */
internal object ServiceCatalog {
    /** Every service, in the file's order. */
    val services: List<ModelService> by lazy { read(load()) }

    /** The service [id]; it must be in the file. */
    fun service(id: String): ModelService = services.firstOrNull { it.id == id } ?: error("No model service $id in $RESOURCE.")

    /** The file as data. */
    fun load(): CatalogData {
        val text = checkNotNull(ServiceCatalog::class.java.classLoader?.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing." }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return JSON.decodeFromString(CatalogData.serializer(), text)
    }

    fun read(data: CatalogData): List<ModelService> = data.services.map { it.service() }

    /** Where the file is, from the class path's root (a path R8 does not move). */
    const val RESOURCE = "cc/noharry/agent/appserver/models/services.json"

    private val JSON = Json { ignoreUnknownKeys = false }
}

@Serializable
internal class CatalogData(val format: Int, val services: List<ServiceData>)

/** One service in the data file; see `services.json` for what each field holds. */
@Serializable
internal class ServiceData(
    val id: String,
    val label: String,
    val source: String,
    val asOf: String,
    val endpoints: List<EndpointData>,
    val hosts: List<String> = emptyList(),
    val keyHint: String = "",
    val modelsUrl: String? = null,
    val listsModels: Boolean = true,
    val listedIdPrefix: String = "",
    val usageFields: UsageFieldsData? = null,
    val errors: List<ErrorData> = emptyList(),
    val retry: RetryData? = null,
    val balance: Boolean = false,
    val relayCheck: Boolean = false,
    val currency: String,
    val pricesSource: String = "",
    val notes: List<String> = emptyList(),
    val models: List<ModelData> = emptyList(),
) {
    fun service(): ModelService = ModelService(
        id = id,
        label = label,
        endpoints = endpoints.map { ServiceEndpoint(ProviderEndpoint.entries.single { e -> e.wireName == it.endpoint }, it.baseUrl, it.region) },
        hosts = hosts.toSet(),
        keyHint = keyHint,
        models = models.map { it.model(this) },
        modelsUrl = modelsUrl,
        listsModels = listsModels,
        listedIdPrefix = listedIdPrefix,
        usageFields = usageFields?.let { UsageFields(it.cacheHit, it.cacheMiss) },
        errors = errors.map { ErrorRule(ModelFailureKind.valueOf(it.kind), it.status, it.pattern) },
        retry = retry?.let { RetryPolicy(it.attempts, it.initialBackoffMillis, it.maxBackoffMillis) },
        balance = balance,
        relayCheck = relayCheck,
        source = source,
        asOf = asOf,
    )
}

@Serializable
internal class EndpointData(val endpoint: String, val baseUrl: String, val region: String = "")

@Serializable
internal class UsageFieldsData(val cacheHit: String, val cacheMiss: String? = null)

@Serializable
internal class ErrorData(val kind: String, val status: Int? = null, val pattern: String? = null, val source: String)

@Serializable
internal class RetryData(val attempts: Int, val initialBackoffMillis: Long, val maxBackoffMillis: Long, val source: String)

/** A model: its id, other ids the service takes for it ([aliases]), its facts, its price, and the page they came from. */
@Serializable
internal class ModelData(
    val id: String,
    val aliases: List<String> = emptyList(),
    val source: String,
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    val imageInput: Boolean? = null,
    val toolCalls: Boolean? = null,
    val reasoning: Boolean? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val price: PriceData? = null,
    val note: String = "",
) {
    fun model(service: ServiceData): ServiceModel = ServiceModel(
        id = id,
        facts = ModelFacts(contextWindow, maxOutputTokens, imageInput, toolCalls, reasoning, reasoningEfforts),
        price = price?.let {
            ModelPrice(
                input = it.input,
                output = it.output,
                cacheRead = it.cachedInput,
                cacheWrite = it.cacheWrite,
                source = service.id,
                currency = service.currency,
                asOf = service.asOf,
            )
        },
        aliases = aliases.toSet(),
    )
}

/** Per million tokens, in the service's currency: input (not from the cache), [cachedInput], [cacheWrite] and output. */
@Serializable
internal class PriceData(
    val input: Double,
    val output: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null,
    val tier: String = "",
)
