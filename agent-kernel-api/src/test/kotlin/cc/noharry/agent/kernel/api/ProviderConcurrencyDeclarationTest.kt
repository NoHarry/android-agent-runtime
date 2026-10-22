@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConcurrencyDeclarationTest {
    @Test
    fun namedFactoriesEncodeExactModesAndCaps() {
        val threadSafe = ProviderConcurrencyDeclaration.threadSafe(3)
        val serialized = ProviderConcurrencyDeclaration.serialized()
        val sessionFactory = ProviderConcurrencyDeclaration.sessionFactory(2)

        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE, threadSafe.mode)
        assertEquals(3, threadSafe.maxConcurrentCalls)
        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED, serialized.mode)
        assertEquals(1, serialized.maxConcurrentCalls)
        assertEquals(ProviderConcurrencyMode.SESSION_FACTORY, sessionFactory.mode)
        assertEquals(2, sessionFactory.maxConcurrentCalls)
        assertEquals(
            ProviderConcurrencyDeclaration.DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS,
            ProviderConcurrencyDeclaration.threadSafe().maxConcurrentCalls,
        )
    }

    @Test
    fun rejectsNonPositiveCaps() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConcurrencyDeclaration.threadSafe(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConcurrencyDeclaration.sessionFactory(0)
        }
    }

    @Test
    fun rejectsSerializedCapOtherThanOne() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderConcurrencyDeclaration(
                mode = ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED,
                maxConcurrentCalls = 2,
            )
        }
        assertTrue(error.message.orEmpty().contains("exactly 1"))
    }

    @Test
    fun enumSupportsExactlyTheThreeCandidateModes() {
        assertEquals(
            listOf(
                ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE,
                ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED,
                ProviderConcurrencyMode.SESSION_FACTORY,
            ),
            ProviderConcurrencyMode.entries,
        )
    }
}
