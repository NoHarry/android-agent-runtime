@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.exec.js

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import org.junit.Assert.assertEquals
import org.junit.Test

class ZiplineExecEngineConcurrencyDeclarationTest {
    @Test
    fun declaresSerializedConcurrency() {
        assertEquals(ProviderConcurrencyDeclaration.serialized(), ZiplineExecEngine().providerConcurrency)
    }
}
