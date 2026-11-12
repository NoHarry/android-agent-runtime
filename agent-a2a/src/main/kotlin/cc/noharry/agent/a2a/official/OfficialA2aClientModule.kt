package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientFactory
import cc.noharry.agent.a2a.A2aClientModule
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.http.AndroidA2AHttpClient
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.spec.AgentCard

/**
 * An A2A client module backed by the official A2A Java SDK 1.1.0.Final, which speaks A2A 1.0 (JSON-RPC over HTTP, with
 * the SDK's card checks); [cc.noharry.agent.a2a.direct.DirectA2aClientModule] speaks A2A 0.3 and needs nothing more.
 *
 * This module compiles against the SDK without carrying it: an app that uses this client adds
 * `org.a2aproject.sdk:a2a-java-sdk-client` and `org.a2aproject.sdk:a2a-java-sdk-http-client-android` at 1.1.0.Final,
 * each without `org.a2aproject.sdk:a2a-java-sdk-common` (this module has its own copy of those classes, which Android
 * accepts). Made where they are missing, it throws [IllegalStateException] naming them; an app shrunk by R8 that names
 * this class without them fails to build instead, R8 naming the SDK's missing classes.
 */
@ExperimentalA2aClientApi
public class OfficialA2aClientModule public constructor() : A2aClientModule {
    init {
        SDK_CLASSES.firstOrNull { (_, load) -> runCatching { load() }.isFailure }?.let { (artifact, _) ->
            throw IllegalStateException(missingSdkMessage(artifact))
        }
    }

    /** Returns a factory that creates executable official-SDK challenger clients. */
    override fun factory(): A2aClientFactory = A2aClientFactory { config ->
        OfficialA2aClient(config, DefaultOfficialSdkBridge(config))
    }
}

/** A class of each SDK artifact the official client needs, loaded only to see that the app has it. */
private val SDK_CLASSES: List<Pair<String, () -> Class<*>>> = listOf(
    "a2a-java-sdk-client" to { Client::class.java },
    "a2a-java-sdk-http-client-android" to { AndroidA2AHttpClient::class.java },
    "a2a-java-sdk-client-transport-jsonrpc" to { JSONRPCTransport::class.java },
    "a2a-java-sdk-spec" to { AgentCard::class.java },
)

private fun missingSdkMessage(artifact: String): String =
    "OfficialA2aClientModule needs the official A2A Java SDK, which this app does not have (org.a2aproject.sdk:" +
        "$artifact is missing). Add org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final and " +
        "org.a2aproject.sdk:a2a-java-sdk-http-client-android:1.1.0.Final, each excluding " +
        "org.a2aproject.sdk:a2a-java-sdk-common (agent-a2a has its own copy), or use DirectA2aClientModule, which needs " +
        "nothing more."
