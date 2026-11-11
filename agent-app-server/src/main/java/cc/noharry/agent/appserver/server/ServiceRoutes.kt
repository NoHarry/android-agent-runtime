package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.knownServices
import cc.noharry.agent.appserver.model.serviceNamed
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.ServiceBalanceParams
import cc.noharry.agent.appserver.protocol.ServiceBalanceResult
import cc.noharry.agent.appserver.protocol.ServiceCheckParams
import cc.noharry.agent.appserver.protocol.ServiceCheckView
import cc.noharry.agent.appserver.protocol.ServiceListResult
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The model services the adapters know ([ServiceRequests]): their list, and a service's balance and its check of a
 * relay, which its adapter answers ([ProviderAdapter.balance], [ProviderAdapter.check]). Keys go in only: a request's
 * key is resolved here, as a run on the connection would use it, and never sent back. A request that names no key (none
 * typed, no connection) is refused, as a key is never guessed: another profile's would go to the service.
 */
internal class ServiceRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(ServiceRequests.LIST) { ServiceListResult(knownServices().map { it.second.view() }) }
        on(ServiceRequests.BALANCE) { balance(it) }
        on(ServiceRequests.CHECKED) { params ->
            val (adapter, service) = offering(params.service, ModelService::relayCheck)
            withContext(Dispatchers.IO) {
                val key = keyOf(params.connection)
                adapter.checked(app, service, params.connection.profile(key), key) ?: ServiceCheckView(hasKey = key.isNotBlank())
            }
        }
        on(ServiceRequests.CHECK) { params ->
            val (adapter, service) = offering(params.service, ModelService::relayCheck)
            val key = withContext(Dispatchers.IO) { keyOf(params.connection) }
            adapter.check(app, service, params.connection.profile(key), key) ?: ServiceCheckView(hasKey = key.isNotBlank())
        }
    }

    /**
     * The balance, or why it could not be read. A connection with no key at all says so, and nothing is sent: an empty
     * key would come back from the service as a rejected one.
     */
    private suspend fun balance(params: ServiceBalanceParams): ServiceBalanceResult {
        val (adapter, service) = offering(params.service, ModelService::balance)
        val connection = params.connection
        val relayUrl = params.url?.takeIf { connection != null }
        val typed = params.apiKey.trim().takeIf { relayUrl == null }.orEmpty()
        if (typed.isEmpty() && connection == null) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, NO_KEY_NAMED)
        val key = typed.ifEmpty { withContext(Dispatchers.IO) { keyOf(checkNotNull(connection)) } }
        if (key.isBlank()) return failed(withContext(Dispatchers.IO) { noKey(checkNotNull(connection)) })
        return try {
            adapter.balance(app, service, key, relayUrl)?.let { ServiceBalanceResult(balance = it) } ?: failed(finding(FindingCode.MODEL_PROBE_NOT_BALANCE))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(error.finding())
        }
    }

    /** The adapter and service named [id], which must offer what [offers] says; a client's mistake otherwise. */
    private fun offering(id: String, offers: (ModelService) -> Boolean): Pair<ProviderAdapter, ModelService> {
        val named = serviceNamed(id) ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "No adapter knows the model service $id.")
        if (!offers(named.second)) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "The model service $id does not answer this request.")
        return named
    }

    /** The key a run on [connection] would use: the one typed, else the one the configuration holds for it. */
    private fun keyOf(connection: ProviderConnection): String = ProviderConfig.load(app).let { config ->
        config.effectiveApiKey(connection.profile(config.keyFor(connection.profileId, connection.apiKey)))
    }

    /** Why [connection] has no key: its profile's, typed nowhere, saved nowhere, carried by no preset. */
    private fun noKey(connection: ProviderConnection): Finding {
        val config = ProviderConfig.load(app)
        val label = config.profiles.firstOrNull { it.id == connection.profileId }?.displayLabel ?: connection.label.ifBlank { connection.profileId }
        return finding(FindingCode.MODEL_MISSING_API_KEY, label, config.expectedPaths.joinToString("\n"))
    }
}

private fun failed(cause: Finding) = ServiceBalanceResult(failure = Finding(FindingCode.MODEL_BALANCE_FAILED, more = listOf(cause)))

private const val NO_KEY_NAMED = "services/balance needs apiKey, or connection with the profileId whose key to use"

/** The profile as filled in, for a check; one of the Messages endpoint needs a model, the selected one or the first. */
private fun ProviderConnection.profile(apiKey: String): ProviderProfile =
    ProviderProfile(profileId, label, provider, apiKey, model, baseUrl.trim(), endpoint)
