package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.net.URI
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.TransportProtocol

@OptIn(ExperimentalA2aClientApi::class)
internal object OfficialAgentCardPolicy {
    fun validate(
        remote: RemoteAgentRef,
        card: AgentCard,
        allowLoopbackHttp: Boolean,
    ): Result {
        if (remote.expectedIdentity != null && remote.expectedIdentity != card.name()) {
            return Result.Rejected(A2aClientError.Discovery("identity_mismatch"))
        }
        OfficialUriPolicy.validate(remote, allowLoopbackHttp)?.let {
            return Result.Rejected(it)
        }
        val interfaces = card.supportedInterfaces().orEmpty().filter {
            it.protocolBinding() == TransportProtocol.JSONRPC.asString()
        }
        if (interfaces.isEmpty()) {
            return Result.Rejected(A2aClientError.Protocol("jsonrpc_interface_missing"))
        }
        var advertisedUriError: A2aClientError? = null
        for (agentInterface in interfaces) {
            val advertised = agentInterface.url().toUriOrNull()
            if (advertised == null) {
                advertisedUriError = A2aClientError.Discovery("advertised_endpoint_invalid")
                continue
            }
            val uriError = OfficialUriPolicy.validate(advertised, allowLoopbackHttp)
            if (uriError != null) {
                advertisedUriError = uriError.asAdvertisedEndpointError()
                continue
            }
            if (OfficialUriPolicy.canonicallyMatches(remote.endpoint, advertised)) {
                return Result.Accepted(agentInterface)
            }
            advertisedUriError = A2aClientError.Discovery("advertised_endpoint_mismatch")
        }
        return Result.Rejected(
            advertisedUriError ?: A2aClientError.Discovery("advertised_endpoint_invalid"),
        )
    }

    private fun String?.toUriOrNull(): URI? =
        this?.takeIf { it.isNotBlank() }?.let { runCatching { URI(it) }.getOrNull() }

    private fun A2aClientError.asAdvertisedEndpointError(): A2aClientError.Discovery =
        if (reason == "insecure_remote_uri") {
            A2aClientError.Discovery("advertised_endpoint_insecure")
        } else {
            A2aClientError.Discovery("advertised_endpoint_invalid")
        }

    sealed interface Result {
        data class Accepted(val agentInterface: AgentInterface) : Result

        data class Rejected(val error: A2aClientError) : Result
    }
}
