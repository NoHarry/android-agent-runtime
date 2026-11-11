@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.models.deepseek
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.webLookup
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebSearchTest {
    // DeepSeek's lookups come from its adapter, which its plugin installs; when a run uses them is the server's
    // (WebLookupDispatchTest).
    @org.junit.Before
    fun installDeepSeek() = AppServerTesting.running(PluginAdditions(providerAdapters = listOf(DeepSeekAdapter)))

    @org.junit.After
    fun noAdapters() = AppServerTesting.reset()

    private val sent = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun resultsComeBackWithSummariesAndABusyServiceIsAskedAgain() = runBlocking {
        val search = provider(
            429 to """{"error":{"message":"QPS limit exceeded"}}""",
            200 to """{"results":[
                {"title":"被越秀硬控了","url":"https://www.nfnews.com/a.html","snippet":"短","summary":"国医市集 10月17日至19日","site_name":"nfnews.com","date":"2026-10-17T16:00:00+00:00"},
                {"title":"只有摘要","url":"https://b.example.com","snippet":"片段","summary":""},
                {"title":"没有地址","url":""}]}""",
        )

        val results = search.search("广州 周末 活动", 5)

        assertEquals(2, sent.size)
        assertTrue(sent.last(), sent.last().contains("\"query\":\"广州 周末 活动\"") && sent.last().contains("\"count\":5"))
        assertEquals(listOf("https://www.nfnews.com/a.html", "https://b.example.com"), results.map { it.url })
        assertEquals("国医市集 10月17日至19日", results[0].summary)
        assertEquals("nfnews.com", results[0].site)
        assertEquals("片段", results[1].summary)
    }

    @Test
    fun aPageTheServiceCannotFetchIsNoFallback() = runBlocking {
        assertNull(provider(404 to """{"error":{"message":"no content"}}""").fetch("https://gone.example.com"))
        val page = provider(200 to """{"title":"文章","content":"# 文章\n\n正文"}""").fetch("https://a.example.com")
        assertEquals("# 文章\n\n正文", page?.markdown)
    }

    @Test
    fun aDeepSeekProfileWithAKeyHasTheServicesSearchAndFetch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deepSeek = ProviderProfile("ds", "DeepSeek", ModelProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic")
        val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + deepSeek) }

        assertNotNull(DeepSeekAdapter.lookup(context, config, deepSeek))
        assertNotNull(config.webLookup(context, deepSeek))
        assertTrue(DeepSeekAdapter.hasLookup(context, config, deepSeek))
    }

    @Test
    fun anyProfilesDeepSeekKeyServesAnotherProfilesPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deepSeek = ProviderProfile("ds3", "DeepSeek", ModelProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic")
        val gateway = ProviderProfile("gw", "Gateway", ModelProvider.OPENAI, "k", "deepseek-flash", "https://openrouter.ai/api/v1")
        val loaded = ProviderConfig.load(context)
        val keyed = loaded.copy(profiles = loaded.profiles + deepSeek + gateway)
        val unkeyed = loaded.copy(profiles = loaded.profiles.filterNot { it.isDeepSeek() } + gateway)

        // The gateway keeps its own search and still gets pages through the Fetch API, with a DeepSeek key from any
        // profile.
        assertNull(DeepSeekAdapter.lookup(context, keyed, gateway))
        assertNotNull(keyed.webLookup(context, gateway))
        assertTrue(keyed.profiles.any { DeepSeekAdapter.hasLookup(context, keyed, it) })
        assertNull(unkeyed.webLookup(context, gateway))
        assertFalse(unkeyed.profiles.any { DeepSeekAdapter.hasLookup(context, unkeyed, it) })
    }

    private fun provider(vararg answers: Pair<Int, String>): DeepSeekWebSearch {
        val queue = ArrayDeque(answers.toList())
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            sent += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val (code, body) = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return DeepSeekWebSearch(apiKey = { "test-key" }, baseUrl = "https://api.deepseek.com/v1/tools", client = client)
    }
}
