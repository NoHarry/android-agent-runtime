package cc.noharry.agent.appserver.server

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.packages.PackageInstaller
import cc.noharry.agent.appserver.packages.PackageSecrets
import cc.noharry.agent.appserver.packages.PackageStore
import cc.noharry.agent.appserver.packages.PackageTrust
import cc.noharry.agent.appserver.packages.TestPackages
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A client installs, lists, switches and removes a package with the protocol's requests, and reads why one is refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginRoutesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val routes by lazy {
        val host = PluginHost(app, listOf(cards), Switches(), Dispatchers.Unconfined).apply { startAll() }
        val installer = PackageInstaller(
            host, PackageStore(temp.newFolder("plugins")), PackageSecrets(temp.newFolder("secrets")), PackageTrust(),
            Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
        RouteMap().apply { with(PluginRoutes(app, { host }, { installer })) { install() } }
    }
    private val cards = object : AppServerPlugin {
        override val id = "cards"
        override fun apply(ctx: PluginContext) = Unit
    }

    @Test
    fun aPackageIsInstalledSwitchedAndRemovedOverTheProtocol() = runBlocking {
        val data = Base64.getEncoder().encodeToString(TestPackages.build())
        val checked = call("plugins/install", """{"data":"$data"}""").jsonObject
        val pkg = checked.getValue("package").jsonObject
        assertEquals("com.example.trains", pkg.text("id"))
        assertEquals(TestPackages.keyHex(), pkg.getValue("publisher").jsonObject.text("key"))
        assertEquals("internet", pkg.getValue("permissions").jsonObject.getValue("network").jsonArray.single().jsonObject.text("reach"))
        assertTrue(checked.getValue("needsAcknowledgement").jsonPrimitive.boolean)
        val installId = checked.text("installId")

        assertEquals("NEEDS_ACKNOWLEDGEMENT", refusal("plugins/confirm", """{"installId":"$installId","secrets":{"apiKey":"k1"}}"""))
        val listed = call("plugins/confirm", """{"installId":"$installId","secrets":{"apiKey":"k1"},"acknowledgeUntrusted":true}""")
        val installed = listed.plugin("com.example.trains")
        assertEquals("package", installed.text("source"))
        assertTrue(installed.getValue("running").jsonPrimitive.boolean)
        assertEquals("built-in", call("plugins/list", "{}").plugin("cards").text("source"))

        assertFalse(call("plugins/set", """{"id":"com.example.trains","enabled":false}""").plugin("com.example.trains").getValue("running").jsonPrimitive.boolean)
        assertTrue(call("plugins/set", """{"id":"com.example.trains","enabled":true}""").plugin("com.example.trains").getValue("running").jsonPrimitive.boolean)
        assertEquals("NOT_A_PACKAGE_PLUGIN", refusal("plugins/remove", """{"id":"cards"}"""))
        val left = call("plugins/remove", """{"id":"com.example.trains"}""").jsonObject.getValue("plugins").jsonArray
        assertEquals(listOf("cards"), left.map { it.jsonObject.text("id") })
    }

    @Test
    fun aSourceMustBeOneAndAnAddressHttps() = runBlocking {
        assertEquals("BAD_SOURCE", refusal("plugins/install", "{}"))
        assertEquals("BAD_SOURCE", refusal("plugins/install", """{"url":"http://example.com/a.agentplugin"}"""))
        assertEquals("BAD_SOURCE", refusal("plugins/install", """{"url":"https://example.com/a","data":"AA=="}"""))
        assertEquals("BAD_SOURCE", refusal("plugins/install", """{"file":"file:///data/data/x/files/secret.json"}"""))
        assertEquals("NOT_A_PACKAGE", refusal("plugins/install", """{"data":"AAAA"}"""))
        assertEquals("INSTALL_EXPIRED", refusal("plugins/confirm", """{"installId":"nothing"}"""))
    }

    private suspend fun call(method: String, params: String): JsonElement {
        val route = routes.entries.single { it.key == method }.value
        return route(JsonRpcRequest(JsonPrimitive(1), method, kotlinx.serialization.json.Json.parseToJsonElement(params)))
    }

    /** The problem a refused request names. */
    private suspend fun refusal(method: String, params: String): String {
        val refused = try {
            call(method, params)
            error("$method was not refused")
        } catch (rejected: JsonRpcException) {
            rejected
        }
        assertEquals(refused.message, JsonRpcErrorCodes.REJECTED, refused.code)
        return (refused.data as JsonObject).text("problem")
    }

    private fun JsonElement.plugin(id: String): JsonObject =
        jsonObject.getValue("plugins").jsonArray.map { it.jsonObject }.single { it.text("id") == id }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private class Switches : PluginSwitches {
        private val off = mutableSetOf<String>()
        override fun isOn(id: String) = id !in off
        override fun set(id: String, on: Boolean) { if (on) off -= id else off += id }
    }
}
