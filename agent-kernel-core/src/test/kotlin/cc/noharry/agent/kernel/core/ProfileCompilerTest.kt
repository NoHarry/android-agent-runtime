package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class ProfileCompilerTest {
    @Test
    fun compile_producesImmutablePluginSnapshot() {
        val key = testKey("snap")
        val plugins = mutableListOf(
            testPlugin("alpha", testDescriptor("alpha", provides = setOf(key))) {
                provide(key, "value", testSerializedConcurrency)
            },
        )
        val blueprint = ProfileCompiler().compile(testProfile("snap-profile"), plugins)
        plugins += testPlugin("beta")
        assertEquals(listOf("alpha"), blueprint.installedPluginIds)
        assertEquals("snap-profile", blueprint.profile.id)
        assertEquals("value", blueprint.resolveShared(key))
        blueprint.close()
    }

    @Test
    fun compile_optionalRequirementDoesNotBlock() {
        val optional = testKey("maybe")
        val required = testKey("must")
        val blueprint = ProfileCompiler().compile(
            testProfile(),
            listOf(
                testPlugin("provider", testDescriptor("provider", provides = setOf(required))) {
                    provide(required, "present", testSerializedConcurrency)
                },
                testPlugin(
                    "consumer",
                    testDescriptor(
                        "consumer",
                        requires = setOf(required),
                        optionalRequires = setOf(optional),
                    ),
                ),
            ),
        )
        assertEquals(listOf("provider", "consumer"), blueprint.installedPluginIds)
        assertEquals("present", blueprint.resolveShared(required))
        assertNull(blueprint.resolveShared(optional))
        blueprint.close()
    }

    @Test
    fun compileFailure_doesNotYieldUsableBlueprint() {
        val closed = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            ProfileCompiler().compile(
                testProfile(),
                listOf(
                    testPlugin("alpha") { own(trackingResource("alpha", closed)) },
                    testPlugin("zeta") { error("compile failed") },
                ),
            )
        }
        assertEquals(listOf("alpha"), closed)
    }

    @Test
    fun compile_rejectsKernelMismatch() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileCompiler().compile(
                cc.noharry.agent.kernel.api.AgentProfile("other", KernelApiVersion(0, 9)),
                emptyList(),
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.PROFILE_KERNEL_MISMATCH))
    }

    @Test
    fun blueprint_sessionResolutionUsesInternalPath() {
        val key = testKey("session")
        val blueprint = ProfileCompiler().compile(
            testProfile(),
            listOf(
                testPlugin("factory", testDescriptor("factory", provides = setOf(key))) {
                    provide(key, SessionCapabilityFactory { context -> "for-${context.sessionId}" })
                },
            ),
        )
        val context = SessionCapabilityContext("s-1")
        assertEquals("for-s-1", blueprint.resolveSession(context, key))
        blueprint.closeSession(context)
        blueprint.close()
    }
}
