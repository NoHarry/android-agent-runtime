package cc.noharry.agent.kernel.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import cc.noharry.agent.kernel.api.model.ModelClient;
import cc.noharry.agent.kernel.api.model.ModelEvent;
import cc.noharry.agent.kernel.api.model.ModelRequest;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import org.junit.Test;

/** A Java host implements the runtime's interfaces writing only what it changes: the rest are JVM default methods. */
public class JavaHostDefaultsTest {
    @Test
    public void aJavaHookWritesNothingItDoesNotChange() {
        RuntimeHook hook = new RuntimeHook() {};

        assertNotNull(hook);
    }

    @Test
    public void aJavaModelClientWritesOnlyComplete() {
        ModelClient client = new ModelClient() {
            @Override
            public Flow<ModelEvent> complete(ModelRequest request) {
                return FlowKt.emptyFlow();
            }
        };

        assertEquals(ProviderConcurrencyDeclaration.Companion.serialized(), client.getProviderConcurrency());
    }
}
