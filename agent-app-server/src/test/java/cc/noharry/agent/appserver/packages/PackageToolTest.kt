@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the publishing tool (tools/agent-plugin/agentplugin.py) builds is what the server takes: packages it built and
 * signed (kept here, their keys thrown away), the one with scripts run in the real engine, and, where python3 and
 * openssl are installed, both examples built now.
 */
class PackageToolTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aPackageTheToolBuiltAndSignedChecks() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)).use { it.readBytes() }
        val checked = checkPackage(bytes)
        assertEquals(FIXTURE_KEY, checked.publisherKey)
        assertEquals(listOf("com.example.weather", "1.0.0"), listOf(checked.manifest.id, checked.manifest.version))
        assertEquals(listOf("forecast"), checked.manifest.skills.map { it.name })
        assertEquals("api.example.com", checked.manifest.mcpServers.single().host)
        assertEquals(2, checked.ruleLines().size)
    }

    @Test
    fun aPackageWithScriptsTheToolBuiltAndSignedChecksAndItsScriptsRun() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(SCRIPTED)).use { it.readBytes() }
        val checked = checkPackage(bytes)
        assertEquals(SCRIPTED_KEY, checked.publisherKey)
        assertEquals(listOf("current_weather", "notes_digest", "echo_request"), checked.manifest.tools.map { it.name })
        assertEquals(listOf(true, true, false), checked.manifest.tools.map { it.readOnly })
        // A tool that names no time has 10 s.
        assertEquals(listOf(20, 10, 10), checked.manifest.tools.map { it.timeoutSeconds })
        assertEquals(listOf("mcp__notes__list_notes"), checked.manifest.hostTools)

        val provider = scriptToolProvider(checked, mapOf("apiKey" to "k1"), trusted = true, host = listOf(Notes()), client = scriptHttpClient()) {}
        val context = ToolCallContext("s", "r", "q")
        val digest = runBlocking { provider.callTool("notes_digest", "{}", context) }
        assertEquals("""{"count":4,"latest":["call the plumber","buy milk","book the train"]}""", digest.resultJson)
        val far = runBlocking { provider.callTool("current_weather", """{"latitude": 91, "longitude": 8}""", context) }
        assertFalse(far.success)
        assertTrue(far.resultJson, "latitude: above 90" in far.resultJson)
        val effects = runBlocking { provider.listTools() }.map { it.effectKind }
        assertEquals(listOf(RuntimeEffectKind.READ_ONLY.name, RuntimeEffectKind.READ_ONLY.name, RuntimeEffectKind.UNKNOWN.name), effects)
    }

    @Test
    fun aPackageTheToolBuildsNowChecks() {
        assumeTrue("python3 and openssl are needed", runs("python3", "--version") && runs("openssl", "version"))
        val key = File(temp.root, "publisher.key")
        val printed = tool("keygen", key.path)
        val publicKey = Regex("public key:\\s+([0-9a-f]+)").find(printed)!!.groupValues[1]

        val signed = File(temp.root, "signed.agentplugin")
        tool("build", EXAMPLE.path, "--key", key.path, "--out", signed.path)
        assertEquals(publicKey, checkPackage(signed.readBytes()).publisherKey)
        tool("verify", signed.path, "--key", publicKey)

        val unsigned = File(temp.root, "unsigned.agentplugin")
        tool("build", EXAMPLE.path, "--unsigned", "--out", unsigned.path)
        assertNull(checkPackage(unsigned.readBytes()).publisherKey)

        val scripted = File(temp.root, "scripted.agentplugin")
        tool("build", EXAMPLE_SCRIPTS.path, "--key", key.path, "--out", scripted.path)
        val tools = checkPackage(scripted.readBytes()).manifest.tools
        assertEquals(listOf("current_weather" to 20, "notes_digest" to 10, "echo_request" to 10), tools.map { it.name to it.timeoutSeconds })
        val stray = File(temp.root, "stray").also { EXAMPLE_SCRIPTS.copyRecursively(it) }
        File(stray, "scripts/extra.js").writeText("function run() { return 1; }\n")
        assertFalse(runs("python3", TOOL.path, "build", stray.path, "--unsigned", "--out", File(temp.root, "stray.agentplugin").path))
    }

    @Test
    fun theToolRefusesATimeTheServerWouldRefuse() {
        assumeTrue("python3 is needed", runs("python3", "--version"))
        val manifest = File(EXAMPLE_SCRIPTS, "plugin.json").readText()
        val asked = "\"timeoutSeconds\": 20"
        check(asked in manifest)
        fun builds(seconds: String): Boolean {
            val dir = File(temp.root, "t${seconds.hashCode()}").also { EXAMPLE_SCRIPTS.copyRecursively(it, overwrite = true) }
            File(dir, "plugin.json").writeText(manifest.replace(asked, "\"timeoutSeconds\": $seconds"))
            return runs("python3", TOOL.path, "build", dir.path, "--unsigned", "--out", File(dir, "out.agentplugin").path)
        }
        listOf("0", "121", "-1", "2.5", "3.0", "1e1", "\"10\"", "true", "null").forEach { assertFalse(it, builds(it)) }
        listOf("1", "120").forEach { assertTrue(it, builds(it)) }
    }

    /** A read-only list_notes, as a run's configured MCP server named notes gives it. */
    private class Notes : ToolProvider {
        override val providerId = "notes"
        override suspend fun listTools() = listOf(ToolDescriptor("list_notes", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name))
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) =
            ToolCallResult(true, """{"notes":["water the plants","call the plumber","buy milk","book the train"]}""")
    }

    /** Runs the tool; its output, failing the test when it fails. */
    private fun tool(vararg args: String): String {
        val process = ProcessBuilder(listOf("python3", TOOL.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "agentplugin.py ${args.first()}: $output" }
        return output
    }

    private fun runs(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).redirectErrorStream(true).start().run {
            inputStream.readBytes()
            waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && exitValue() == 0
        }
    }.getOrDefault(false)

    private companion object {
        const val FIXTURE = "packages/com.example.weather-1.0.0.agentplugin"
        const val FIXTURE_KEY = "3059301306072a8648ce3d020106082a8648ce3d030107034200048b0691ed89314b5f072840d0d1d9387a565aa3d85376f4955df7e" +
            "0024b5d4b2c5d0011c0fbb9547d4852510482facf1f9df7c26bcbc3db5041c2a0b73b918ec1"
        const val SCRIPTED = "packages/com.example.scripted-1.0.0.agentplugin"
        const val SCRIPTED_KEY = "3059301306072a8648ce3d020106082a8648ce3d03010703420004dbf10b66b53d6059df16afb9e289dd4dd4323cc7146bed7d09534f07" +
            "228468c6a4edfd3275b7a02bdd8d04a69f1da371540fd1d9cd8091b54c32cc1be3572a3c"
        const val TIMEOUT_SECONDS = 60L

        /** The tool and its example, from this module's directory (where Gradle runs its tests). */
        val TOOL = File("../tools/agent-plugin/agentplugin.py")
        val EXAMPLE = File("../tools/agent-plugin/example")
        val EXAMPLE_SCRIPTS = File("../tools/agent-plugin/example-scripts")
    }
}
