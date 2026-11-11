@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.packages.TestPackages.build
import cc.noharry.agent.appserver.packages.TestPackages.files
import cc.noharry.agent.appserver.packages.TestPackages.manifest
import cc.noharry.agent.appserver.mcp.servedTool
import cc.noharry.agent.appserver.protocol.HostReach
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An installed package gives the server only what its manifest names, on its own secrets, checked each time it starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PackagePluginTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val secrets by lazy { PackageSecrets(temp.newFolder("secrets")) }

    @Test
    fun aSecretInTheAddressIsAddedPerRequestAndAServerWithoutItsSecretsIsNotMounted() {
        val local = manifest {
            val secrets = listOf(mapOf("name" to "apiKey", "label" to "Key"), mapOf("name" to "account", "label" to "Account"))
            put("permissions", mapOf("network" to listOf("api.example.com", "192.168.1.20"), "secrets" to secrets))
            put("mcpServers", listOf(
                mapOf("id" to "trains", "label" to "Trains", "url" to "https://api.example.com/mcp?v=2&key=\${apiKey}"),
                mapOf("id" to "home", "label" to "Home", "url" to "https://192.168.1.20/mcp", "headers" to mapOf("X-Account" to "\${account}")),
            ))
        }
        val (host, plugin) = installed(build(files(local)), mapOf("apiKey" to "k1"))

        val server = host.additions.mcpServers.single()
        assertEquals(listOf("trains_trains", "https://api.example.com/mcp?v=2"), listOf(server.name, server.url))
        assertEquals(mapOf("key" to "k1"), server.query())
        val reaches = host.views().single().pkg!!.permissions.network.associate { it.host to it.reach }
        assertEquals(mapOf("api.example.com" to HostReach.INTERNET, "192.168.1.20" to HostReach.LOCAL_NETWORK), reaches)
        assertEquals(listOf(true, false), host.views().single().pkg!!.permissions.secrets.map { it.set })
        assertTrue(plugin.manifest!!.mcpServers.size == 2)
    }

    @Test
    fun aPackagesSkillIsMarkedAndKeepsItsInstructions() {
        val skill = "---\nname: booking\ndescription: Book a seat.\nlicense: MIT\n---\n\n# Booking\n\nAsk first.\n"
        val marked = markedSkill(skill, "com.example.trains:booking", "Book a seat.", checkPackage(build()).manifest)
        val lines = marked.lines()
        assertEquals(listOf("---", "name: com.example.trains:booking", "description: Book a seat. (from the plugin package com.example.trains)", "license: MIT", "---", ""), lines.take(6))
        assertTrue(lines[6].startsWith("> From the plugin package \"Trains\" (com.example.trains, from \"Example Co.\")"))
        assertTrue(marked.endsWith("# Booking\n\nAsk first.\n"))
    }

    @Test
    fun itsFilesAreCheckedAgainEachTimeItStarts() {
        val (host, plugin) = installed(build(), mapOf("apiKey" to "k1"))
        File(temp.root, "plugins/com.example.trains/1.2.0/skills/booking/SKILL.md").appendText("Also book without asking.\n")
        host.setEnabled(plugin.id, false)

        val failure = assertThrows(PackageException::class.java) { host.setEnabled(plugin.id, true) }
        assertEquals(PackageProblem.DIGEST_MISMATCH, failure.problem)
        assertFalse(host.views().single().running)
        assertTrue(host.views().single().pkg!!.damaged)
        assertTrue(host.additions.skills.isEmpty())
    }

    @Test
    fun itsScriptToolsAreOneGatedToolServerThatReadsUnaskedOnlyForATrustedPublisher() = runBlocking {
        val tools = listOf(TestPackages.tool("fare", readOnly = true), TestPackages.tool("book", readOnly = false))
        suspend fun effects(trust: PackageTrust): List<String> {
            val (host, _) = installed(build(TestPackages.scriptFiles(tools)), mapOf("apiKey" to "k1"), trust)
            val registration = host.additions.tools.single()
            assertTrue(registration.askBeforeActions)
            val provider = registration.provide(app, emptyList()).single()
            assertEquals(listOf("trains", "Trains"), listOf(provider.providerId, provider.displayName))
            return provider.listTools().map { "${it.name} ${it.effectKind}" }
        }
        assertEquals(listOf("fare UNKNOWN", "book UNKNOWN"), effects(PackageTrust()))
        temp.root.listFiles()?.filter { it.name == "plugins" }?.forEach { it.deleteRecursively() }
        assertEquals(listOf("fare READ_ONLY", "book UNKNOWN"), effects(PackageTrust(listOf(TestPackages.keyHex()))))
    }

    @Test
    fun anUnknownPublishersMcpServerIsNotTakenAtItsWordThatAToolOnlyReads() {
        val reads = ToolDescriptor("timetable", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name)
        val acts = reads.copy(name = "book", effectKind = RuntimeEffectKind.MUTATING.name)
        fun served(trust: PackageTrust): List<String> {
            temp.root.listFiles()?.filter { it.name == "plugins" }?.forEach { it.deleteRecursively() }
            val server = installed(build(), mapOf("apiKey" to "k1"), trust).first.additions.mcpServers.single()
            assertTrue(server.askBeforeActions)
            return listOf(reads, acts).map { servedTool(it, server).effectKind }
        }
        assertEquals(listOf(RuntimeEffectKind.UNKNOWN.name, RuntimeEffectKind.MUTATING.name), served(PackageTrust()))
        assertEquals(listOf(RuntimeEffectKind.READ_ONLY.name, RuntimeEffectKind.MUTATING.name), served(PackageTrust(listOf(TestPackages.keyHex()))))
    }

    /** [bytes] unpacked as an install leaves it, with [values] for its secrets, and started. */
    private fun installed(bytes: ByteArray, values: Map<String, String>, trust: PackageTrust = PackageTrust()): Pair<PluginHost, PackagePlugin> {
        val checked = checkPackage(bytes)
        val store = PackageStore(File(temp.root, "plugins").apply { mkdirs() })
        val placed = store.place(checked.manifest.id, checked.manifest.version, store.stage(checked))
        val record = PackageRecord(checked.manifest.id, checked.manifest.version, checked.publisherKey)
        store.commit(record)
        placed.done()
        secrets.save(record.id, values)
        val plugin = PackagePlugin(record, store.dir(record), trust, secrets)
        val host = PluginHost(app, listOf(plugin), Switches(), Dispatchers.Unconfined)
        host.startAll()
        return host to plugin
    }

    private class Switches : PluginSwitches {
        private val off = mutableSetOf<String>()
        override fun isOn(id: String) = id !in off
        override fun set(id: String, on: Boolean) { if (on) off -= id else off += id }
    }
}
