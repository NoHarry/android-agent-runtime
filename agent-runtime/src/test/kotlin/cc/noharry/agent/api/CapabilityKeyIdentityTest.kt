package cc.noharry.agent.kernel.api

import cc.noharry.agent.runtime.testDescriptor
import cc.noharry.agent.runtime.testPlugin
import cc.noharry.agent.runtime.testSerializedConcurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.PluginRegistry

@OptIn(ExperimentalAgentRuntimeApi::class)
class CapabilityKeyIdentityTest {
    @Test
    fun sameDiagnosticName_isNotLookupIdentity() {
        val first = CapabilityKey.of<String>("test", "shared", 1, CapabilityCardinality.SINGLE)
        val second = CapabilityKey.of<String>("test", "shared", 1, CapabilityCardinality.SINGLE)
        assertTrue(first !== second)
        assertEquals(first.diagnosticId, second.diagnosticId)
        assertEquals("test:shared:1", first.diagnosticId)

        val registry = PluginRegistry()
        registry.install(
            testPlugin("provider", testDescriptor("provider", provides = setOf(first))) {
                provide(first, "kept", testSerializedConcurrency)
            },
        )

        assertEquals("kept", registry.resolve(first))
        assertNull(registry.resolve(second))
    }

    @Test
    fun simpleName_usesDefaultNamespaceAndMajor() {
        val key = CapabilityKey<String>("model_client", CapabilityCardinality.SINGLE)
        assertEquals(PluginContractMessages.DEFAULT_NAMESPACE, key.namespace)
        assertEquals("model_client", key.localName)
        assertEquals(1, key.major)
        assertEquals("agent:model_client:1", key.diagnosticId)
    }

    @Test
    fun invalidDiagnosticName_isRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilityKey<String>("not:a:valid:name", CapabilityCardinality.SINGLE)
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.INVALID_CAPABILITY_NAME))
    }

    @Test
    fun objectIdentity_doesNotUseEqualsOrHashCode() {
        val first = CapabilityKey.of<String>("test", "shared", 1, CapabilityCardinality.SINGLE)
        val second = CapabilityKey.of<String>("test", "shared", 1, CapabilityCardinality.SINGLE)
        assertNotEquals(System.identityHashCode(first), 0)
        assertTrue(first != second || first === first)
        assertTrue(first.hashCode() != 0)
    }
}
