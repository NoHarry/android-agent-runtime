package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.net.URI
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class OfficialAgentCardPolicyTest {
    @Test
    fun acceptsCanonicalEndpointMatch() {
        val expected = AgentInterface(JSONRPC, "HTTPS://agent.example.com/rpc?mode=full")
        val result = validate(
            remote = remote(
                "https://AGENT.EXAMPLE.COM:443/rpc/?mode=full",
                expectedIdentity = "weather-agent",
            ),
            interfaces = listOf(expected),
        )

        assertSame(expected, (result as OfficialAgentCardPolicy.Result.Accepted).agentInterface)
    }

    @Test
    fun acceptsEmptyAndRootPathAsEquivalent() {
        val expected = AgentInterface(JSONRPC, "https://agent.example.com/")
        val result = validate(
            remote = remote("https://agent.example.com"),
            interfaces = listOf(expected),
        )

        assertSame(expected, (result as OfficialAgentCardPolicy.Result.Accepted).agentInterface)
    }

    @Test
    fun rejectsIdentityMismatch() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc", expectedIdentity = "expected-agent"),
            name = "substituted-agent",
        )

        assertRejected(result, A2aClientError.Discovery("identity_mismatch"))
    }

    @Test
    fun rejectsCardWithoutJsonRpcInterface() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(AgentInterface("HTTP+JSON", "https://agent.example.com/rpc")),
        )

        assertRejected(result, A2aClientError.Protocol("jsonrpc_interface_missing"))
    }

    @Test
    fun rejectsCrossHostEndpointWithoutLeakingIt() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(AgentInterface(JSONRPC, "https://attacker.example/rpc")),
        )

        val error = assertRejected(result, A2aClientError.Discovery("advertised_endpoint_mismatch"))
        assertFalse(error.toString().contains("attacker.example"))
    }

    @Test
    fun rejectsPathMismatch() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(AgentInterface(JSONRPC, "https://agent.example.com/admin")),
        )

        assertRejected(result, A2aClientError.Discovery("advertised_endpoint_mismatch"))
    }

    @Test
    fun rejectsQueryMismatch() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc?tenant=one"),
            interfaces = listOf(AgentInterface(JSONRPC, "https://agent.example.com/rpc?tenant=two")),
        )

        assertRejected(result, A2aClientError.Discovery("advertised_endpoint_mismatch"))
    }

    @Test
    fun rejectsAdvertisedUserInfoWithoutLeakingIt() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(AgentInterface(JSONRPC, "https://user:secret@agent.example.com/rpc")),
        )

        val error = assertRejected(result, A2aClientError.Discovery("advertised_endpoint_invalid"))
        assertFalse(error.toString().contains("secret"))
    }

    @Test
    fun rejectsNonLoopbackHttp() {
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(AgentInterface(JSONRPC, "http://agent.example.com/rpc")),
            allowLoopbackHttp = true,
        )

        assertRejected(result, A2aClientError.Discovery("advertised_endpoint_insecure"))
    }

    @Test
    fun allowsLoopbackHttpWhenExplicitlyEnabled() {
        val expected = AgentInterface(JSONRPC, "http://127.0.0.1:80/rpc/")
        val result = validate(
            remote = remote("http://127.0.0.1/rpc"),
            interfaces = listOf(expected),
            allowLoopbackHttp = true,
        )

        assertSame(expected, (result as OfficialAgentCardPolicy.Result.Accepted).agentInterface)
    }

    @Test
    fun selectsOnlyMatchingJsonRpcInterface() {
        val expected = AgentInterface(JSONRPC, "https://agent.example.com/rpc")
        val result = validate(
            remote = remote("https://agent.example.com/rpc"),
            interfaces = listOf(
                AgentInterface(JSONRPC, "https://other.example/rpc"),
                AgentInterface("HTTP+JSON", "https://agent.example.com/rpc"),
                expected,
            ),
        )

        assertSame(expected, (result as OfficialAgentCardPolicy.Result.Accepted).agentInterface)
    }

    private fun validate(
        remote: RemoteAgentRef,
        name: String = "weather-agent",
        interfaces: List<AgentInterface> = listOf(AgentInterface(JSONRPC, remote.endpoint.toString())),
        allowLoopbackHttp: Boolean = false,
    ): OfficialAgentCardPolicy.Result = OfficialAgentCardPolicy.validate(
        remote = remote,
        card = card(name, interfaces),
        allowLoopbackHttp = allowLoopbackHttp,
    )

    private fun remote(endpoint: String, expectedIdentity: String? = null): RemoteAgentRef =
        RemoteAgentRef("weather", URI(endpoint), expectedIdentity = expectedIdentity)

    private fun card(name: String, interfaces: List<AgentInterface>): AgentCard = AgentCard.builder()
        .name(name)
        .description("test agent")
        .version("1")
        .capabilities(AgentCapabilities(false, false, false, emptyList()))
        .defaultInputModes(emptyList())
        .defaultOutputModes(emptyList())
        .skills(emptyList())
        .supportedInterfaces(interfaces)
        .build()

    private fun assertRejected(
        result: OfficialAgentCardPolicy.Result,
        expected: A2aClientError,
    ): A2aClientError {
        val error = (result as OfficialAgentCardPolicy.Result.Rejected).error
        assertEquals(expected, error)
        return error
    }

    private companion object {
        val JSONRPC: String = TransportProtocol.JSONRPC.asString()
    }
}
