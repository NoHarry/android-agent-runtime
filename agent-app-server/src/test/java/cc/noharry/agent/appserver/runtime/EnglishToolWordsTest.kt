@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.skills.customSkillTemplate
import cc.noharry.agent.appserver.skills.subagentInput
import cc.noharry.agent.runtime.SubagentSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's own tools as a persona without a language reads them: names, descriptions and schemas all in English.
 * Words the host supplies (its places, its persona) are its own, read by the host's tests. Each plugin module
 * reads its own tools so ([cc.noharry.agent.appserver.server.AppServerPlugin]).
 */
class EnglishToolWordsTest {
    @Test
    fun theGenericPersonaReadsNoChinese() = runBlocking {
        // The plugins' tools are read so in their own modules (the plan tool in Bothy's agent-app-plans).
        assertNoChinese(customSkillTemplate())
        // With a plugin offering cards, the child's picture rule is in English too.
        AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions(offers = setOf(cc.noharry.agent.appserver.protocol.ServerFeatures.CARDS)))
        try {
            val input = subagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "weather", "Find tomorrow's weather", 1, null, false))
            assertTrue(input, input.contains("Image: https://"))
            assertNoChinese(input)
        } finally {
            AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions.NONE)
        }
    }

    private fun assertNoChinese(text: String) =
        assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
}
