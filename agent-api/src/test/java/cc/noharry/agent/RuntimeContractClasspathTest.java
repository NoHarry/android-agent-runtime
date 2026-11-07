package cc.noharry.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

/** This module's test classpath contains agent-api and its dependencies, without agent-runtime. */
public class RuntimeContractClasspathTest {
    @Test
    public void runtimeAndDiagnosticsLinkWithoutTheImplementation() throws Exception {
        assertEquals(RuntimeDiagnostics.class, AgentRuntime.class.getMethod("getDiagnostics").getReturnType());
        assertEquals(AgentThread.class, AgentRuntime.class.getMethod("startThread", ThreadOptions.class).getReturnType());
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("cc.noharry.agent.runtime.AgentRuntimeBuilder"));
    }
}
