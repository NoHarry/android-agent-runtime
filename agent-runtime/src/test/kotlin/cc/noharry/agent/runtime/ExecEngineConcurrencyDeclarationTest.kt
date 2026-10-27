@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecEngineConcurrencyDeclarationTest {
    @Test
    fun declaredEngineUsesExplicitSerializedConcurrency() {
        val engine = object : ExecEngine {
            override val providerConcurrency: ProviderConcurrencyDeclaration =
                ProviderConcurrencyDeclaration.serialized()

            override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult =
                ExecResult(ok = true)
        }
        assertEquals(ProviderConcurrencyDeclaration.serialized(), engine.providerConcurrency)
    }
}
