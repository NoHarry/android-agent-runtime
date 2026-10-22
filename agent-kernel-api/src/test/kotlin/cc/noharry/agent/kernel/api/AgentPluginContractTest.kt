@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPluginContractTest {
    @Test
    fun descriptorHasNoDefaultImplSoIdMayDefaultToDescriptor() {
        val defaultImpls = Class.forName("cc.noharry.agent.kernel.api.AgentPlugin\$DefaultImpls")
        val names = defaultImpls.declaredMethods.map { it.name }.toSet()
        assertTrue(names.contains("getId"))
        assertTrue(!names.contains("getDescriptor"))
    }

    @Test
    fun idDefaultsToDescriptorId() {
        val plugin = object : AgentPlugin {
            override val descriptor: AgentPluginDescriptor = AgentPluginDescriptor(
                id = "from-descriptor",
                pluginVersion = PluginVersion(1, 0, 0),
                kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            )
            override fun install(scope: AgentPluginScope) = Unit
        }
        assertEquals("from-descriptor", plugin.descriptor.id)
        assertEquals("from-descriptor", plugin.id)
        repeat(8) {
            assertEquals(plugin.descriptor.id, plugin.id)
        }
    }

    @Test
    fun fullV2OverrideKeepsMatchingIdAndDescriptor() {
        val plugin = object : AgentPlugin {
            override val id: String = "full-v2"
            override val descriptor: AgentPluginDescriptor = AgentPluginDescriptor(
                id = "full-v2",
                pluginVersion = PluginVersion(1, 0, 0),
                kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            )
            override fun install(scope: AgentPluginScope) = Unit
        }
        assertEquals("full-v2", plugin.id)
        assertEquals("full-v2", plugin.descriptor.id)
        assertEquals(1, plugin.descriptor.pluginVersion.major)
    }
}
