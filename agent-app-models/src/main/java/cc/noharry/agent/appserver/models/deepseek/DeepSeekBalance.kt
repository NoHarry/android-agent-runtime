package cc.noharry.agent.appserver.models.deepseek
import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.httpFailure
import cc.noharry.agent.appserver.protocol.BalanceAmountView
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What DeepSeek's `/user/balance` says of a key: whether it can still be used and, per currency, the total with what was
 * topped up and what was granted.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
data class DeepSeekBalance(val available: Boolean, val amounts: List<DeepSeekAmount>)

@cc.noharry.agent.appserver.AppServerPluginApi
data class DeepSeekAmount(val currency: String, val total: String, val toppedUp: String, val granted: String)

internal fun parseDeepSeekBalance(body: String): DeepSeekBalance {
    val root = Json.parseToJsonElement(body).jsonObject
    val amounts = root["balance_infos"]?.jsonArray.orEmpty().map { it.jsonObject }.map { info ->
        DeepSeekAmount(info.text("currency"), info.text("total_balance"), info.text("topped_up_balance"), info.text("granted_balance"))
    }
    return DeepSeekBalance(root["is_available"]?.jsonPrimitive?.booleanOrNull ?: amounts.isNotEmpty(), amounts)
}

/** The balance as the screens get it, to word themselves. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun DeepSeekBalance.view(): BalanceView =
    BalanceView(available, amounts.map { BalanceAmountView(it.currency, it.total, it.toppedUp, it.granted) })

/**
 * Asks DeepSeek with [apiKey] (or the relay at [url]); the key goes only there, in the header, never into a log. A
 * failure the server can name is a [FoundException].
 */
@cc.noharry.agent.appserver.AppServerPluginApi
suspend fun fetchDeepSeekBalance(
    apiKey: String,
    client: OkHttpClient = BALANCE_CLIENT,
    url: String = DEEPSEEK_BALANCE_URL,
): Result<DeepSeekBalance> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${apiKey.trim()}").header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> parseDeepSeekBalance(body)
                response.code == UNAUTHORIZED -> throw FoundException(finding(FindingCode.MODEL_BALANCE_KEY_REJECTED))
                else -> throw FoundException(httpFailure(response.code, body.errorMessage()))
            }
        }
    }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun String.errorMessage(): String? =
    runCatching { Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject?.text("message") }.getOrNull()?.ifBlank { null }?.take(MAX_ERROR_CHARS)

private val BALANCE_CLIENT = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

/** DeepSeek's own balance query. */
internal const val DEEPSEEK_BALANCE_URL = "https://api.deepseek.com/user/balance"
private const val UNAUTHORIZED = 401
private const val MAX_ERROR_CHARS = 120
