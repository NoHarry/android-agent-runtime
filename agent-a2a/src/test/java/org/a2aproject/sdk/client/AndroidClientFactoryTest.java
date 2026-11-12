package org.a2aproject.sdk.client;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransport;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.Test;

public final class AndroidClientFactoryTest {
    @Test
    public void createsAndClosesClientWithPrebuiltTransport() {
        AtomicBoolean transportClosed = new AtomicBoolean(false);
        ClientTransport transport = (ClientTransport) Proxy.newProxyInstance(
                ClientTransport.class.getClassLoader(),
                new Class<?>[] {ClientTransport.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        transportClosed.set(true);
                    }
                    return null;
                });

        Client client = AndroidClientFactory.create(
                agentCard(),
                ClientConfig.builder().build(),
                transport);

        assertNotNull(client);
        client.close();
        assertTrue(transportClosed.get());
    }

    private static AgentCard agentCard() {
        return AgentCard.builder()
                .name("test-agent")
                .description("test agent")
                .version("1")
                .capabilities(new AgentCapabilities(false, false, false, Collections.emptyList()))
                .defaultInputModes(Collections.emptyList())
                .defaultOutputModes(Collections.emptyList())
                .skills(Collections.emptyList())
                .supportedInterfaces(Collections.singletonList(new AgentInterface(
                        TransportProtocol.JSONRPC.asString(),
                        "https://example.com/a2a")))
                .build();
    }
}
