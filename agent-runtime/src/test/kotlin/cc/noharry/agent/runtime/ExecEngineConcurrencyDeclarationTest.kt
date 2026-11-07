@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.ExecRequest
import cc.noharry.agent.spi.ExecResult
import cc.noharry.agent.spi.ExecToolBridge
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
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
