package cc.noharry.agent.appserver.mcp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.transport.HttpRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A plugin's MCP server reaches the host it named and no other: its connection does not follow a redirect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginMcpRedirectTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aRedirectIsAnsweredNotFollowed() = runBlocking {
        MockWebServer().use { elsewhere ->
            MockWebServer().use { named ->
                elsewhere.enqueue(MockResponse().setBody("{}"))
                named.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/mcp")))
                named.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/mcp")))
                val request = HttpRequest("POST", named.url("/mcp").toString(), body = "{}".toByteArray())

                assertEquals(307, AppServerHttpTransport(app, followRedirects = false).execute(request).statusCode)
                assertEquals(0, elsewhere.requestCount)
                // The server's own connections still follow one.
                assertEquals(200, AppServerHttpTransport(app).execute(request).statusCode)
                assertEquals(1, elsewhere.requestCount)
            }
        }
    }
}
