package org.a2aproject.sdk.client;

import java.util.Collections;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransport;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Creates an official SDK client from an Android-compatible, prebuilt transport.
 *
 * <p>This candidate adapter entry point avoids the SDK's ServiceLoader-based builder path, which
 * uses Java 9 APIs that are unavailable on supported Android API levels.
 */
public final class AndroidClientFactory {
    private AndroidClientFactory() {
    }

    /**
     * Creates a client backed by the supplied transport without performing provider discovery.
     *
     * @param agentCard the resolved remote agent card
     * @param clientConfig the official SDK client configuration
     * @param clientTransport the fully constructed Android-compatible transport
     * @return a client that owns and closes {@code clientTransport}
     */
    public static Client create(
            AgentCard agentCard,
            ClientConfig clientConfig,
            ClientTransport clientTransport) {
        return new Client(
                agentCard,
                clientConfig,
                clientTransport,
                Collections.emptyList(),
                null);
    }
}
