package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.tools.LastPrompt
import cc.noharry.agent.appserver.usage.TokenDelta
import cc.noharry.agent.appserver.usage.parseTokenUsage
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * What a run's model calls leave: their token use, failures for the transcript, the last prompt's size, and, when
 * [keepsLog] (the host's [cc.noharry.agent.appserver.server.AppServerConfig.diagnostics]), each request and reply in
 * [logFile].
 */
class AppDiagnostics internal constructor(
    private val logFile: File,
    private val appContext: android.content.Context? = null,
    private val onEntry: (DiagnosticEntry) -> Unit = {},
    private val onTokenUsage: (TokenDelta) -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val keepsLog: Boolean = true,
) {
    /** Where the log is, said in each entry; nothing without one. */
    private val logLine: String get() = if (keepsLog) "log=${logFile.absolutePath}" else ""

    private val nextCallId = AtomicInteger(1)

    suspend fun flush() = DiagnosticWrites.flush(logFile.name)

    fun transport(provider: ModelProvider): HttpTransport =
        LoggingHttpTransport(provider.label, this, AppServerHttpTransport(requireNotNull(appContext)))

    internal fun recordSessionStart(provider: ModelProvider, streamLabel: String, configSummary: String) {
        // File-only: provider transport chatter must not flood the conversation timeline.
        record(
            title = "Diagnostics log",
            body = """
                provider=${provider.label}
                mode=$streamLabel
                config=$configSummary
                ${if (keepsLog) "path=${logFile.absolutePath}" else ""}
            """.trimIndent(),
            notifyUi = false,
        )
    }

    internal fun recordRequest(provider: String, mode: String, request: HttpRequest): Int {
        val callId = nextCallId.getAndIncrement()
        val body = request.bodyText()
        LastPrompt.record(body)
        val summary = request.summary(body)
        val headers = request.redactedHeaders()
        record(
            title = "Provider request #$callId",
            body = """
                provider=$provider mode=$mode method=${request.method} url=${request.url}
                $summary
                headers=$headers
                bodyPrefix=${body.redacted().take(MAX_BODY_PREFIX_CHARS)}
                $logLine
            """.trimIndent(),
            uiBody = """
                provider=$provider mode=$mode method=${request.method} url=${request.url}
                $summary
                headers=$headers
                $logLine
            """.trimIndent(),
            notifyUi = false,
        )
        return callId
    }

    internal fun recordResponse(provider: String, mode: String, callId: Int, response: HttpResponse) {
        val body = response.body.toString(Charsets.UTF_8)
        parseTokenUsage(body)?.let(onTokenUsage)
        val failed = response.statusCode !in 200..299
        record(
            title = "Provider response #$callId",
            body = """
                provider=$provider mode=$mode status=${response.statusCode}
                bodyBytes=${response.body.size} bodySha=${body.sha12()}
                bodyPrefix=${body.redacted().take(MAX_BODY_PREFIX_CHARS)}
                $logLine
            """.trimIndent(),
            uiBody = """
                provider=$provider mode=$mode status=${response.statusCode}
                bodyBytes=${response.body.size} bodySha=${body.sha12()}
                $logLine
            """.trimIndent(),
            notifyUi = failed,
        )
    }

    internal fun recordStreamResponse(provider: String, callId: Int, statusCode: Int) {
        val failed = statusCode !in 200..299
        record(
            title = "Provider stream response #$callId",
            body = "provider=$provider status=$statusCode $logLine".trimEnd(),
            notifyUi = failed,
        )
    }

    internal fun recordStreamDone(provider: String, callId: Int, chunks: Int, bytes: Int, bodyPrefix: String) {
        record(
            title = "Provider stream done #$callId",
            body = """
                provider=$provider chunks=$chunks bytes=$bytes
                bodyPrefix=${bodyPrefix.redacted().take(MAX_BODY_PREFIX_CHARS)}
                $logLine
            """.trimIndent(),
            uiBody = "provider=$provider chunks=$chunks bytes=$bytes $logLine".trimEnd(),
            notifyUi = false,
        )
    }

    /** File-only: how much of this model request repeats the session's previous one (see [PromptPrefix]). */
    internal fun recordPromptPrefix(line: String) {
        record(title = "Prompt cache prefix", body = line, notifyUi = false)
    }

    internal fun recordTokenUsage(delta: TokenDelta) {
        onTokenUsage(delta)
    }

    internal fun recordFailure(provider: String, mode: String, callId: Int, error: Exception) {
        record(
            title = "Provider transport failure #$callId",
            body = "provider=$provider mode=$mode error=${error.message.orEmpty().redacted()} $logLine".trimEnd(),
            notifyUi = true,
        )
    }

    private fun record(
        title: String,
        body: String,
        notifyUi: Boolean,
        uiBody: String = body,
    ) {
        val entry = DiagnosticEntry(title, uiBody.trimEnd())
        if (keepsLog) DiagnosticWrites.publish(logFile,
                """
                    time=${nowMillis()}
                    title=$title
                    ${body.lineSequence().joinToString(separator = "\n")}

                """.trimIndent() + "\n",
        )
        if (notifyUi) onEntry(entry)
    }
}

internal data class DiagnosticEntry(
    val title: String,
    val body: String,
)

internal fun appDiagnostics(
    context: android.content.Context,
    requestId: String,
    onEntry: (DiagnosticEntry) -> Unit,
    onTokenUsage: (TokenDelta) -> Unit,
) =
    AppDiagnostics(
        logFile = File(File(context.filesDir, DEBUG_LOG_DIR), requestId.safeFileName() + ".log"),
        appContext = context,
        onEntry = onEntry,
        onTokenUsage = onTokenUsage,
        keepsLog = AppHost.diagnostics,
    )

private class LoggingHttpTransport(
    private val provider: String,
    private val diagnostics: AppDiagnostics,
    private val delegate: HttpTransport,
) : HttpTransport {
    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        val callId = diagnostics.recordRequest(provider, "stream", request)
        val capture = StreamDiagnosticCapture()
        try {
            delegate.stream(request).collect { event ->
                when (event) {
                    is HttpEvent.Response -> diagnostics.recordStreamResponse(provider, callId, event.statusCode)
                    is HttpEvent.Data -> capture.record(event.bytes)
                    HttpEvent.Done -> {
                        capture.usage?.let(diagnostics::recordTokenUsage)
                        diagnostics.recordStreamDone(
                            provider,
                            callId,
                            capture.chunks,
                            capture.bytes,
                            capture.prefixText(),
                        )
                    }
                }
                emit(event)
            }
        } catch (error: RuntimeException) {
            diagnostics.recordFailure(provider, "stream", callId, error)
            throw error
        } catch (error: IOException) {
            diagnostics.recordFailure(provider, "stream", callId, error)
            throw error
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val callId = diagnostics.recordRequest(provider, "execute", request)
        return try {
            delegate.execute(request).also { response ->
                diagnostics.recordResponse(provider, "execute", callId, response)
            }
        } catch (error: RuntimeException) {
            diagnostics.recordFailure(provider, "execute", callId, error)
            throw error
        } catch (error: IOException) {
            diagnostics.recordFailure(provider, "execute", callId, error)
            throw error
        }
    }
}

private class StreamDiagnosticCapture {
    var chunks: Int = 0
        private set
    var bytes: Int = 0
        private set
    var usage: TokenDelta? = null
        private set
    private val prefix = ByteArrayOutputStream()

    fun record(data: ByteArray) {
        chunks += 1
        bytes += data.size
        parseTokenUsage(data.toString(Charsets.UTF_8))?.let { usage = it }
        val remaining = MAX_BODY_PREFIX_CHARS - prefix.size()
        if (remaining > 0) {
            prefix.write(data, 0, minOf(remaining, data.size))
        }
    }

    fun prefixText(): String =
        prefix.toByteArray().toString(Charsets.UTF_8)
}

private fun HttpRequest.summary(body: String): String =
    jsonStrings(body, "name").distinct().let { toolNames ->
        "model=${jsonString(body, "model")} stream=${jsonScalar(body, "stream")} " +
            "messages=${countJsonKey(body, "role")} roles=${jsonStrings(body, "role").joinToString("/")} " +
            "toolNames=${toolNames.size} toolNameList=${toolNames.take(MAX_NAMES)} " +
            "toolResultMessages=${jsonStrings(body, "role").count { it == "tool" }} " +
            "assistantToolCallMessages=${countJsonKey(body, "tool_calls")} bodyBytes=${body.length} bodySha=${body.sha12()}"
    }

private fun HttpRequest.redactedHeaders(): String =
    headers.map { (name, value) ->
        val safeValue = if (name.lowercase() in SENSITIVE_HEADERS) "<redacted>" else value.redacted()
        "$name=$safeValue"
    }.joinToString(prefix = "{", postfix = "}")

private fun HttpRequest.bodyText(): String =
    body?.toString(Charsets.UTF_8).orEmpty()

internal fun String.redacted(): String {
    var output = replace(SECRET_TOKEN, "sk-REDACTED")
    output = output.replace(BEARER_TOKEN) { match -> match.groupValues[1] + "<redacted>" }
    output = output.replace(API_KEY_FIELD) { match -> match.groupValues[1] + "<redacted>" + match.groupValues[3] }
    return output
}

private fun jsonString(text: String, key: String): String =
    jsonStrings(text, key).firstOrNull().orEmpty()

private fun jsonScalar(text: String, key: String): String =
    Regex(""""$key"\s*:\s*([^,}\]]+)""").find(text)?.groupValues?.get(1).orEmpty()

private fun jsonStrings(text: String, key: String): List<String> =
    Regex(""""$key"\s*:\s*"([^"]*)"""").findAll(text).map { it.groupValues[1] }.toList()

private fun countJsonKey(text: String, key: String): Int =
    Regex(""""$key"\s*:""").findAll(text).count()

private fun String.sha12(): String {
    if (isEmpty()) return "empty"
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(12)
}

private fun String.safeFileName(): String =
    replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(MAX_FILE_NAME_CHARS)

private const val DEBUG_LOG_DIR = "runtime-debug-logs"
private const val MAX_BODY_PREFIX_CHARS = 4096
private const val MAX_FILE_NAME_CHARS = 80
private const val MAX_NAMES = 16
private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "api-key")
private val SECRET_TOKEN = Regex("""sk-[A-Za-z0-9_-]{8,}""")
private val BEARER_TOKEN = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")
private val API_KEY_FIELD = Regex("""(?i)("(?:api[_-]?key|token|secret)"\s*:\s*")([^"]+)(")""")
