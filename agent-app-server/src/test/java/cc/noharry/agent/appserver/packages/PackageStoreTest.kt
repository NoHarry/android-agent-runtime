package cc.noharry.agent.appserver.packages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Installed packages and their secrets live in the server's own directories, and tidying at start deletes only what an
 * install leaves: a host's files beside them, or anything not named as a package, stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PackageStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val root = File(app.filesDir, "app_server/plugins")

    @Test
    fun packagesAndSecretsAreInTheServersOwnDirectories() {
        val hosts = File(app.filesDir, "plugins/x/data.txt").also { it.parentFile!!.mkdirs(); it.writeText("the host's") }
        val half = File(root, "com.example.half/.staging-1").also { it.mkdirs() }

        assertEquals(listOf("com.example.half"), PackageStore(app).tidyAll())

        assertFalse(half.parentFile!!.exists())
        assertEquals("the host's", hosts.readText())
        PackageSecrets(app).save("com.example.half", mapOf("apiKey" to "k1"))
        assertTrue(File(app.noBackupFilesDir, "app_server/plugin-secrets/com.example.half.json").isFile)
    }

    @Test
    fun tidyingTouchesOnlyWhatAnInstallLeaves() {
        val store = PackageStore(root)
        val notAnId = File(root, "notes/today.txt").also { it.parentFile!!.mkdirs(); it.writeText("kept") }
        val notOurs = File(root, "com.host.data/data.txt").also { it.parentFile!!.mkdirs(); it.writeText("kept") }
        // A first install that never committed: staged files and a version put in place, no record.
        File(root, "com.example.never/.staging-2/plugin.json").also { it.parentFile!!.mkdirs(); it.writeText("{}") }
        File(root, "com.example.never/1.0.0").mkdirs()
        File(root, "com.example.never/installed.json.tmp").writeText("{")
        // An installed package with what an update that died left beside it, and a file of someone else's.
        val installed = File(root, "com.example.trains").also { File(it, "1.2.0").mkdirs() }
        File(installed, "installed.json").writeText("""{"id":"com.example.trains","version":"1.2.0","publisherKey":null}""")
        listOf(".staging-3", ".previous-4", "1.1.0").forEach { File(installed, it).mkdirs() }
        val stray = File(installed, "notes.txt").also { it.writeText("kept") }

        assertEquals(listOf("com.example.never"), store.tidyAll())

        assertTrue(notAnId.isFile && notOurs.isFile && stray.isFile)
        assertFalse(File(root, "com.example.never").exists())
        assertEquals(setOf("1.2.0", "installed.json", "notes.txt"), installed.list()!!.toSet())
        assertEquals(listOf("com.example.trains"), store.records().map { it.id })
    }

    @Test
    fun aPackageWhoseRecordCannotBeReadIsKeptAndNamed() {
        val store = PackageStore(root)
        val dir = File(root, "com.example.broken").also { File(it, "1.0.0").mkdirs() }
        File(dir, "installed.json").writeText("not json")
        val other = File(root, "com.example.other").also { File(it, "1.0.0").mkdirs() }
        File(other, "installed.json").writeText("""{"id":"com.example.someone","version":"1.0.0"}""")

        assertEquals(emptyList<String>(), store.tidyAll())

        assertTrue(File(dir, "1.0.0").isDirectory && File(other, "1.0.0").isDirectory)
        assertEquals(listOf("com.example.broken", "com.example.other"), store.unreadable())
        assertTrue(store.records().isEmpty())
    }
}
