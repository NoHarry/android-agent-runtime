@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.packages.TestPackages.build
import cc.noharry.agent.appserver.packages.TestPackages.scriptFiles
import cc.noharry.agent.appserver.packages.TestPackages.tool
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An installed package's tool, from its manifest to its script run in the real engine, as a run calls it. */
class ScriptToolProviderTest {
    private val context = ToolCallContext("session", "run", "request")

    @Test
    fun aPackagesToolRunsItsScriptAndReachesTheHostToolItNamed() {
        val script = """
            function run(input) {
              const weather = host.tool("weather", { city: input.from });
              host.log("asked about " + input.from);
              return { from: input.from, to: input.to, sky: weather.sky };
            }
        """.trimIndent()
        val files = scriptFiles(listOf(tool("fare")), hostTools = listOf("weather")) + ("scripts/fare.js" to script.toByteArray())
        val logs = mutableListOf<String>()
        val provider = scriptToolProvider(checkPackage(build(files)), mapOf("apiKey" to "k1"), trusted = false, host = listOf(Weather()), client = scriptHttpClient()) { logs += it }

        val result = runBlocking { provider.callTool("fare", """{"from": "Bern", "to": "Basel"}""", context) }
        assertEquals("""{"from":"Bern","to":"Basel","sky":"clear"}""", result.resultJson)
        assertEquals(listOf("package com.example.trains, tool fare: asked about Bern"), logs)
        assertEquals(listOf("fare" to RuntimeEffectKind.UNKNOWN.name), runBlocking { provider.listTools() }.map { it.name to it.effectKind })
        assertFalse(runBlocking { provider.callTool("book", "{}", context) }.success)
    }

    @Test
    fun aToolRunsNoLongerThanTheHostLetsItWhateverItAsks() {
        val files = scriptFiles(listOf(tool("fare") { put("timeoutSeconds", 120) })) + ("scripts/fare.js" to "function run() { while (true) {} }".toByteArray())
        val provider = scriptToolProvider(checkPackage(build(files)), mapOf("apiKey" to "k1"), trusted = false, host = emptyList(), client = scriptHttpClient(), maxSeconds = 1) {}
        val started = System.nanoTime()
        val result = runBlocking { provider.callTool("fare", """{"from": "Bern", "to": "Basel"}""", context) }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertFalse(result.success)
        assertTrue(result.resultJson, "the script ran over its time (1 s)" in result.resultJson)
        assertTrue("stopped after $millis ms", millis < 4_000)
    }

    private class Weather : ToolProvider {
        override val providerId = "home"
        override suspend fun listTools() = listOf(ToolDescriptor("weather", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name))
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = ToolCallResult(true, """{"sky":"clear"}""")
    }
}
