package cc.noharry.agent.appserver.models.deepseek
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.protocol.BalanceAmountView
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class DeepSeekBalanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBalanceReadsAsTotalWithToppedUpAndGrantedPerCurrency() = runBlocking {
        val asked = mutableListOf<String>()
        val client = answering(200, """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""") {
            asked += "${it.url} ${it.header("Authorization")}"
        }

        val balance = fetchDeepSeekBalance(" sk-test ", client, "https://api.deepseek.com/user/balance").getOrThrow()

        assertEquals(listOf("https://api.deepseek.com/user/balance Bearer sk-test"), asked)
        assertEquals(BalanceView(true, listOf(BalanceAmountView("CNY", "110.00", "100.00", "10.00"))), balance.view())
        assertEquals(BalanceView(false), parseDeepSeekBalance("""{"is_available":false,"balance_infos":[]}""").view())
    }

    @Test
    fun aRejectedKeySaysSoWithoutEchoingTheAnswer() = runBlocking {
        val rejected = fetchDeepSeekBalance("sk-bad", answering(401, """{"error":{"message":"Authentication Fails, Your api key: ****-bad is invalid"}}"""), URL)
        val busy = fetchDeepSeekBalance("sk-test", answering(503, """{"error":{"message":"Service busy"}}"""), URL)

        assertEquals(finding(FindingCode.MODEL_BALANCE_KEY_REJECTED), rejected.exceptionOrNull()?.finding())
        assertFalse(rejected.exceptionOrNull()!!.message!!.contains("bad"))
        assertEquals(finding(FindingCode.MODEL_HTTP_ERROR, 503, "Service busy"), busy.exceptionOrNull()?.finding())
    }

    @Test
    fun onlyDeepSeeksOwnHostCounts() {
        assertTrue("https://api.deepseek.com/anthropic".isDeepSeekUrl())
        assertTrue(" https://api.deepseek.com ".isDeepSeekUrl())
        assertFalse("https://api.deepseek.com.example.net/v1".isDeepSeekUrl())
        assertFalse("https://openrouter.ai/api/v1".isDeepSeekUrl())
    }

    private fun answering(code: Int, body: String, seen: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

    private companion object {
        const val URL = "https://api.deepseek.com/user/balance"
    }
}
