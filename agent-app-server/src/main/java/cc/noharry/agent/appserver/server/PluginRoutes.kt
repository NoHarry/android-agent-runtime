package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.packages.PackageException
import cc.noharry.agent.appserver.packages.PackageInstaller
import cc.noharry.agent.appserver.packages.fetchPackage
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.protocol.PackageRefusal
import cc.noharry.agent.appserver.protocol.PluginListResult
import cc.noharry.agent.appserver.protocol.PluginRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host's plugins: `plugins/list` and `plugins/set` (turning one on or off while the server runs), and packages a
 * person installs: `plugins/install` (fetch and check), `plugins/confirm` (install and start), `plugins/remove`. A
 * refused package or step answers [JsonRpcErrorCodes.REJECTED] with a [PackageRefusal].
 */
internal class PluginRoutes(
    private val app: Context,
    private val host: () -> PluginHost?,
    private val packages: () -> PackageInstaller?,
) : RouteModule {
    override fun RouteTable.install() {
        on(PluginRequests.LIST) { PluginListResult(withContext(Dispatchers.Main.immediate) { host()?.views().orEmpty() }) }
        on(PluginRequests.SET) { params ->
            val plugins = host() ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "no plugin ${params.id}")
            PluginListResult(refusing { withContext(Dispatchers.Main.immediate) { plugins.setEnabled(params.id, params.enabled) } })
        }
        on(PluginRequests.INSTALL) { params -> refusing { installer().check(fetchPackage(app, params)) } }
        on(PluginRequests.CONFIRM) { params -> PluginListResult(refusing { installer().confirm(params) }) }
        on(PluginRequests.REMOVE) { params -> PluginListResult(refusing { installer().remove(params.id) }) }
    }

    private fun installer(): PackageInstaller =
        packages() ?: throw JsonRpcException(JsonRpcErrorCodes.REJECTED, "this server installs no packages")

    /** [block]'s refusals as the protocol's errors: a package's with its problem, a plugin that would not start. */
    private suspend fun <T> refusing(block: suspend () -> T): T = try {
        block()
    } catch (refused: PackageException) {
        val data = ProtocolJson.encodeToJsonElement(PackageRefusal.serializer(), PackageRefusal(refused.problem, refused.detail))
        throw JsonRpcException(JsonRpcErrorCodes.REJECTED, refused.message.orEmpty(), data)
    } catch (refused: IllegalArgumentException) {
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, refused.message.orEmpty())
    } catch (failed: IllegalStateException) {
        val data = ProtocolJson.encodeToJsonElement(PackageRefusal.serializer(), PackageRefusal(PackageProblem.START_FAILED, failed.javaClass.simpleName))
        throw JsonRpcException(JsonRpcErrorCodes.REJECTED, "it did not start: ${failed.message.orEmpty()}", data)
    }
}
