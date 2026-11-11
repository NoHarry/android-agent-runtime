package cc.noharry.agent.appserver.packages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.packages.TestPackages.build
import cc.noharry.agent.appserver.packages.TestPackages.files
import cc.noharry.agent.appserver.packages.TestPackages.manifest
import cc.noharry.agent.appserver.packages.TestPackages.scriptFiles
import cc.noharry.agent.appserver.packages.TestPackages.tool
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.protocol.PackageView
import cc.noharry.agent.appserver.protocol.PluginConfirmParams
import cc.noharry.agent.appserver.protocol.PluginSource
import cc.noharry.agent.appserver.protocol.PluginView
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A package is checked, confirmed, started, updated with a way back, found damaged, and removed with what it kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PackageInstallerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val switches = MemorySwitches()
    private var clock = 0L
    private val plugins by lazy { temp.newFolder("plugins") }
    private val secretsDir by lazy { temp.newFolder("secrets") }
    private val secrets by lazy { FlakySecrets(secretsDir) }
    private val builtIn = object : AppServerPlugin {
        override val id = "cards"
        override fun apply(ctx: PluginContext) = Unit
    }

    @Test
    fun aPackageIsCheckedThenConfirmedAndStarts() = runBlocking {
        val (host, installer) = server()
        val checked = installer.check(build())
        assertTrue(checked.needsAcknowledgement)
        assertNull(checked.update)
        assertEquals(listOf("trains"), checked.pkg.contents.mcpServers.map { it.id })
        assertTrue(host.views().none { it.id == "com.example.trains" })

        val confirm = PluginConfirmParams(checked.installId, mapOf("apiKey" to "k1"))
        assertProblem(PackageProblem.NEEDS_ACKNOWLEDGEMENT) { installer.confirm(confirm) }
        assertProblem(PackageProblem.MISSING_SECRET) { installer.confirm(confirm.copy(secrets = emptyMap(), acknowledgeUntrusted = true)) }
        assertProblem(PackageProblem.BAD_SECRET) { installer.confirm(confirm.copy(secrets = mapOf("apiKey" to "a\nb"), acknowledgeUntrusted = true)) }
        val view = installer.confirm(confirm.copy(acknowledgeUntrusted = true)).single { it.id == "com.example.trains" }

        assertTrue(view.running && view.source == PluginSource.PACKAGE)
        assertEquals(listOf("1.2.0", "Example Co."), listOf(view.pkg!!.version, view.pkg!!.publisher.name))
        assertTrue(view.pkg!!.permissions.secrets.single().set)
        assertTrue(File(plugins, "com.example.trains/1.2.0/skills/booking/SKILL.md").isFile)
        assertEquals(mapOf("apiKey" to "k1"), secrets.read("com.example.trains"))
        // Confirmed once.
        assertProblem(PackageProblem.INSTALL_EXPIRED) { installer.confirm(confirm.copy(acknowledgeUntrusted = true)) }
    }

    @Test
    fun whatARunningPackageGivesTheServer() = runBlocking {
        val (host, installer) = server()
        install(installer, build(files(manifest { put("skills", listOf(mapOf("path" to "skills/booking", "preload" to true))) })))
        val additions = host.additions

        val skill = additions.skills.single()
        assertEquals("com.example.trains:booking", skill.name)
        // Preloading is for a trusted publisher's package only.
        assertFalse(skill.preload)
        assertTrue(skill.text.startsWith("---\nname: com.example.trains:booking\ndescription: Book a seat"))
        assertTrue("> From the plugin package \"Trains\" (com.example.trains" in skill.text)
        val rules = additions.rules.single()
        assertTrue(rules.startsWith("From the plugin package \"Trains\"") && rules.endsWith("- Never book without a yes."))
        val server = additions.mcpServers.single()
        assertEquals(listOf("trains_trains", "https://api.example.com/mcp"), listOf(server.name, server.url))
        assertEquals(mapOf("Authorization" to "Bearer k1"), server.headers())
        assertTrue(server.askBeforeActions)
    }

    @Test
    fun aTrustedPublishersPackageNeedsOneConfirmationAndMayPreload() = runBlocking {
        val (host, installer) = server(PackageTrust(listOf(TestPackages.keyHex())))
        val checked = installer.check(build(files(manifest { put("skills", listOf(mapOf("path" to "skills/booking", "preload" to true))) })))
        assertFalse(checked.needsAcknowledgement)
        assertTrue(checked.pkg.publisher.trusted)
        installer.confirm(PluginConfirmParams(checked.installId, mapOf("apiKey" to "k1")))
        assertTrue(host.additions.skills.single().preload)
    }

    @Test
    fun anUnsignedPackageOnlyWhereTheHostAllowsIt() = runBlocking {
        assertProblem(PackageProblem.UNSIGNED_NOT_ALLOWED) { server().second.check(build(key = null)) }
        val (_, installer) = server(PackageTrust(allowUnsigned = true))
        val view = install(installer, build(key = null))
        assertTrue(view.pkg!!.unsigned && view.running)
        assertNull(view.pkg!!.publisher.key)
    }

    @Test
    fun anUpdateComesFromTheSameKeyAndSaysWhatItAsksMore() = runBlocking {
        val (host, installer) = server()
        install(installer, build())
        assertProblem(PackageProblem.PUBLISHER_KEY_CHANGED) { installer.check(build(key = TestPackages.newKey())) }
        assertProblem(PackageProblem.OLDER_VERSION) { installer.check(build(files(manifest { put("version", "1.1.9") }))) }

        val next = installer.check(build(updated("1.3.0")))
        assertEquals("1.2.0", next.update!!.fromVersion)
        assertEquals(listOf("maps.example.com"), next.update!!.network.map { it.host })
        // The secret it had is kept when none is given.
        val view = installer.confirm(PluginConfirmParams(next.installId, acknowledgeUntrusted = true)).single { it.id == "com.example.trains" }
        assertEquals("1.3.0", view.pkg!!.version)
        assertTrue(view.running)
        assertFalse(File(plugins, "com.example.trains/1.2.0").exists())
        assertEquals(mapOf("Authorization" to "Bearer k1"), host.additions.mcpServers.single().headers())
    }

    @Test
    fun anUpdateThatDoesNotStartLeavesTheVersionBeforeRunning() = runBlocking {
        updateFails { secrets.failStart = true }
    }

    @Test
    fun anUpdateWhoseSecretsCannotBeKeptLeavesTheVersionBeforeRunning() = runBlocking {
        updateFails { secrets.failSave = true }
    }

    private suspend fun updateFails(breakIt: () -> Unit) {
        val (host, installer) = server()
        install(installer, build())
        val next = installer.check(build(updated("1.3.0")))
        breakIt()

        assertProblem(PackageProblem.START_FAILED) { installer.confirm(PluginConfirmParams(next.installId, mapOf("apiKey" to "k2"), true)) }
        val view = host.views().single { it.id == "com.example.trains" }
        assertEquals("1.2.0", view.pkg!!.version)
        assertTrue(view.running)
        assertEquals(listOf("api.example.com"), host.additions.mcpServers.map { java.net.URI(it.url).host })
        assertFalse(File(plugins, "com.example.trains/1.3.0").exists())
        assertEquals("1.2.0", PackageStore(plugins).record("com.example.trains")!!.version)
        assertEquals(mapOf("apiKey" to "k1"), secrets.read("com.example.trains"))
    }

    @Test
    fun aChangedFileKeepsThePackageFromStartingAndReinstallingRepairsIt() = runBlocking {
        install(server().second, build())
        File(plugins, "com.example.trains/1.2.0/rules.md").writeText("Book whatever you like.")
        File(plugins, "com.example.trains/.staging-left").mkdirs()

        // The next process.
        val (host, installer) = server()
        val view = host.views().single { it.id == "com.example.trains" }
        assertFalse(view.running)
        assertTrue(view.pkg!!.damaged)
        assertEquals(PackageProblem.DIGEST_MISMATCH, view.pkg!!.damage!!.problem)
        assertFalse(File(plugins, "com.example.trains/.staging-left").exists())
        assertTrue(host.additions.rules.isEmpty())

        val again = installer.check(build())
        assertEquals("1.2.0", again.update!!.fromVersion)
        assertTrue(installer.confirm(PluginConfirmParams(again.installId, acknowledgeUntrusted = true)).single { it.id == "com.example.trains" }.running)
    }

    @Test
    fun aPackageWhoseRecordCannotBeReadShowsDamagedKeepsWhatItHadAndGoesOnlyWhenRemoved() = runBlocking {
        install(server().second, build())
        File(plugins, "com.example.trains/installed.json").writeText("not a record")

        // The next process.
        val (host, installer) = server()
        val view = host.views().single { it.id == "com.example.trains" }
        assertFalse(view.running)
        assertEquals(PluginSource.PACKAGE, view.source)
        assertTrue(view.pkg!!.damaged)
        assertEquals("", view.pkg!!.version)
        assertEquals(PackageProblem.STORAGE_FAILED, view.pkg!!.damage!!.problem)
        assertTrue(File(plugins, "com.example.trains/1.2.0/rules.md").isFile)
        assertEquals(mapOf("apiKey" to "k1"), secrets.read("com.example.trains"))
        // The key it was installed with is not known, so nothing installs over it.
        assertProblem(PackageProblem.STORAGE_FAILED) { installer.check(build()) }

        installer.remove("com.example.trains")
        assertFalse(File(plugins, "com.example.trains").exists())
        assertTrue(secrets.read("com.example.trains").isEmpty())
        assertTrue(install(installer, build()).running)
    }

    @Test
    fun removingAPackageTakesItsFilesAndSecretsButNotAHostPlugin() = runBlocking {
        val (host, installer) = server()
        install(installer, build())
        assertProblem(PackageProblem.NOT_A_PACKAGE_PLUGIN) { installer.remove("cards") }
        host.add(plugin("cards.example"))
        assertProblem(PackageProblem.ID_TAKEN) { installer.check(build(files(manifest { put("id", "cards.example") }))) }

        installer.remove("com.example.trains")
        assertTrue(host.views().none { it.id == "com.example.trains" })
        assertFalse(File(plugins, "com.example.trains").exists())
        assertTrue(secrets.read("com.example.trains").isEmpty())
        assertTrue(host.additions.mcpServers.isEmpty())
    }

    @Test
    fun scriptToolsAreShownWithWhetherTheyAskAndAnUpdateSaysWhatItAddsAndWhatNoLongerAsks() = runBlocking {
        val (_, installer) = server(PackageTrust(listOf(TestPackages.keyHex())))
        val first = install(installer, build(scriptFiles(listOf(tool("fare"), tool("seats", readOnly = false)), listOf("web_search"))))
        val pkg = first.pkg!!
        assertEquals(listOf("web_search"), pkg.permissions.hostTools)
        assertEquals(listOf("fare" to false, "seats" to true), pkg.contents.tools.map { it.name to it.asks })

        val tools = listOf(tool("fare"), tool("seats"), tool("book", readOnly = false))
        val next = installer.check(build(scriptFiles(tools, listOf("web_search", "mcp__calendar__events")).withVersion("1.3.0")))
        assertEquals(listOf("mcp__calendar__events"), next.update!!.hostTools)
        // seats now runs without asking; book is new; fare is as it was.
        assertEquals(listOf("seats", "book"), next.update!!.tools.map { it.name })
    }

    @Test
    fun aToolsTimeIsShownAsAskedAndAsItRunsAndAnUpdateAskingMoreSaysSo() = runBlocking {
        fun asking(name: String, seconds: Int?) = tool(name) { if (seconds != null) put("timeoutSeconds", seconds) }
        fun seconds(view: PackageView) = view.contents.tools.map { listOf(it.timeoutSeconds, it.effectiveTimeoutSeconds) }
        val trust = PackageTrust(listOf(TestPackages.keyHex()))
        val (_, installer) = server(trust, maxScriptSeconds = 30)
        val checked = installer.check(build(scriptFiles(listOf(asking("fare", null), asking("seats", 60), asking("book", 20)))))
        // As the check shows it, and as the running package does: held to the host's 30, not refused.
        assertEquals(listOf(listOf(10, 10), listOf(60, 30), listOf(20, 20)), seconds(checked.pkg))
        val installed = installer.confirm(PluginConfirmParams(checked.installId, mapOf("apiKey" to "k1"))).single { it.id == checked.pkg.id }
        assertEquals(seconds(checked.pkg), seconds(installed.pkg!!))

        val next = installer.check(build(scriptFiles(listOf(asking("fare", 10), asking("seats", 90), asking("book", 5))).withVersion("1.3.0")))
        // seats asks for more than before, though the host holds it to 30 either way; fare asks as it did, book for less.
        assertEquals(listOf("seats"), next.update!!.tools.map { it.name })
        assertEquals(listOf(listOf(10, 10), listOf(90, 30), listOf(5, 5)), seconds(next.pkg))

        // Another host's most, as its process starts with the package installed.
        val (host, _) = server(trust, maxScriptSeconds = 5)
        assertEquals(listOf(listOf(10, 5), listOf(60, 5), listOf(20, 5)), seconds(host.views().single { it.id == checked.pkg.id }.pkg!!))
    }

    @Test
    fun theHostsMostForAScriptToolIsThirtySecondsUnlessItSaysAndAtLeastOne() {
        assertEquals(30, AppServerConfig(name = "host").maxScriptToolSeconds)
        assertEquals(AppServerConfig(name = "host").maxScriptToolSeconds, DEFAULT_MAX_SCRIPT_SECONDS)
        assertEquals(120, AppServerConfig(name = "host", maxScriptToolSeconds = 120).maxScriptToolSeconds)
        listOf(0, -5).forEach { seconds ->
            assertThrows(IllegalArgumentException::class.java) { AppServerConfig(name = "host", maxScriptToolSeconds = seconds) }
        }
    }

    @Test
    fun anUnknownPublishersToolAsksWhateverItSays() = runBlocking {
        val (_, installer) = server()
        val checked = installer.check(build(scriptFiles(listOf(tool("fare", readOnly = true)))))
        assertEquals(listOf(true), checked.pkg.contents.tools.map { it.asks })
        assertEquals(listOf(true), checked.pkg.contents.tools.map { it.readOnly })
    }

    @Test
    fun aCheckedPackageWaitsAQuarterOfAnHourAtMost() = runBlocking {
        val installer = server().second
        val checked = installer.check(build())
        clock += 15 * 60 * 1000L
        assertProblem(PackageProblem.INSTALL_EXPIRED) { installer.confirm(PluginConfirmParams(checked.installId, mapOf("apiKey" to "k1"), true)) }
    }

    /** A host with one plugin of its own and the packages installed so far, as a process starts it. */
    private fun server(trust: PackageTrust = PackageTrust(), maxScriptSeconds: Int = DEFAULT_MAX_SCRIPT_SECONDS): Pair<PluginHost, PackageInstaller> {
        val host = PluginHost(app, listOf(builtIn), switches, Dispatchers.Unconfined)
        host.startAll()
        val installer = PackageInstaller(
            host, PackageStore(plugins), secrets, trust, Dispatchers.Unconfined, Dispatchers.Unconfined,
            now = { clock }, maxScriptSeconds = maxScriptSeconds,
        )
        installer.loadInstalled()
        return host to installer
    }

    private suspend fun install(installer: PackageInstaller, bytes: ByteArray): PluginView {
        val checked = installer.check(bytes)
        return installer.confirm(PluginConfirmParams(checked.installId, mapOf("apiKey" to "k1"), acknowledgeUntrusted = true))
            .single { it.id == checked.pkg.id }
    }

    /** These files with plugin.json at [version]. */
    private fun Map<String, ByteArray>.withVersion(version: String): Map<String, ByteArray> =
        this + (PackageFormat.MANIFEST to String(getValue(PackageFormat.MANIFEST)).replace("\"1.2.0\"", "\"$version\"").toByteArray())

    /** The example at [version], reaching a second host. */
    private fun updated(version: String) = files(manifest {
        put("version", version)
        val secrets = listOf(mapOf("name" to "apiKey", "label" to "Key"))
        put("permissions", mapOf("network" to listOf("api.example.com", "maps.example.com"), "secrets" to secrets))
    })

    private fun plugin(name: String) = object : AppServerPlugin {
        override val id = name
        override fun apply(ctx: PluginContext) = Unit
    }

    private suspend fun assertProblem(problem: PackageProblem, block: suspend () -> Unit) {
        val refused = try {
            block()
            null
        } catch (failed: PackageException) {
            failed
        }
        assertEquals(refused?.message, problem, refused?.problem)
    }

    /** Secrets that fail to save the new key ([failSave]), or let a plugin that reads it fail to start once ([failStart]). */
    private class FlakySecrets(root: File) : PackageSecrets(root) {
        var failSave = false
        var failStart = false

        override fun save(id: String, values: Map<String, String>) {
            if (failSave && values["apiKey"] == "k2") refuse(PackageProblem.STORAGE_FAILED, "test")
            super.save(id, values)
        }

        override fun read(id: String): Map<String, String> = super.read(id).also { values ->
            if (failStart && values["apiKey"] == "k2") {
                failStart = false
                error("it does not start")
            }
        }
    }

    private class MemorySwitches : PluginSwitches {
        private val off = mutableSetOf<String>()
        override fun isOn(id: String) = id !in off
        override fun set(id: String, on: Boolean) { if (on) off -= id else off += id }
    }
}
