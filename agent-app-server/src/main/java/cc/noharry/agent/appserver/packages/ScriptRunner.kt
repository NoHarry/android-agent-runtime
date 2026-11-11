@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.exec.js.ZiplineExecEngine
import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.ExecRequest
import cc.noharry.agent.spi.ExecResult
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The limits of one call of a script tool: its time in all, host calls included (what its tool asks, at most the
 * host's [maxSeconds]: [secondsFor]), memory, host calls, sizes. Only the time is a package's to ask for.
 */
internal class ScriptLimits(
    /** The most seconds the host lets one call run, whatever its tool asks (`AppServerConfig.maxScriptToolSeconds`). */
    val maxSeconds: Int = DEFAULT_MAX_SCRIPT_SECONDS,
    val memoryBytes: Long = 16L * 1024 * 1024,
    val hostCalls: Int = 20,
    val resultBytes: Int = 64 * 1024,
    val argumentBytes: Int = 64 * 1024,
    val sentBytes: Int = 64 * 1024,
    val readBytes: Int = 256 * 1024,
    val logLines: Int = 20,
    val logChars: Int = 500,
) {
    init {
        require(maxSeconds >= 1) { "A script tool's call needs at least a second." }
    }

    /** The seconds a call of [tool] runs. */
    fun secondsFor(tool: ManifestTool): Int = scriptToolSeconds(tool, maxSeconds)
}

/** The seconds a call of [tool] runs: what it asks for, held to the host's [maxSeconds]. */
internal fun scriptToolSeconds(tool: ManifestTool, maxSeconds: Int): Int = minOf(tool.timeoutSeconds, maxSeconds)

/** The host's most for one call of a script tool when it sets none, as `AppServerConfig.maxScriptToolSeconds`. */
internal const val DEFAULT_MAX_SCRIPT_SECONDS = 30

/**
 * What a package's scripts reach through the host: the [network] hosts it named, its secrets ([secretNames], and
 * their [values], which only the host reads, to fill in `${name}` as it sends a request) and the [hostTools] it named.
 */
internal class ScriptGrant(
    val packageId: String,
    val network: Set<String>,
    val secretNames: Set<String>,
    val values: Map<String, String>,
    val hostTools: Set<String>,
)

/**
 * Runs one call of a package's script tool in the sandbox ([ExecEngine], QuickJS through Zipline by default): the
 * call's arguments checked against the tool's input schema, then the script with `run(input)` called and its return
 * value read back as JSON; a thrown error is the tool's failure, as the model reads it, and so is running over the
 * call's time ([ScriptLimits.secondsFor], host calls included), named in seconds. The script reaches the host
 * only through `host.http` and `host.tool` (each a host call through the engine's bridge, [ScriptBridge]) and
 * `host.log` (lines kept in the sandbox and written to the app's log when the call ends). The engine runs the script
 * again from the start after each host call with the earlier answers replayed, so a script must make the same calls
 * each time (no time or random numbers in them); one that does not stops with an error.
 */
internal class ScriptRunner(
    private val grant: ScriptGrant,
    private val http: ScriptHttp,
    private val hostTools: ScriptHostTools,
    private val log: (String) -> Unit,
    private val limits: ScriptLimits = ScriptLimits(),
    private val engine: () -> ExecEngine = { ZiplineExecEngine(limits.hostCalls, limits.memoryBytes) },
) {
    suspend fun call(tool: ManifestTool, script: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val input = try {
            input(tool, argumentsJson)
        } catch (refused: ScriptRefusal) {
            return failure(refused.message)
        }
        val seconds = limits.secondsFor(tool)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        val bridge = ScriptBridge(http, hostTools, deadline, context)
        val request = ExecRequest(context.runId, scriptCode(script, input, limits), TimeUnit.SECONDS.toMillis(seconds.toLong()), outputTokens(limits))
        val result = withContext(Dispatchers.IO) { engine().run(request, bridge) }
        return finish(tool, result, seconds)
    }

    /** The call's arguments, a JSON object within its size that its tool's input schema takes. */
    private fun input(tool: ManifestTool, argumentsJson: String): JsonObject {
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > limits.argumentBytes) refused("arguments: over ${limits.argumentBytes / KIB} KiB")
        val parsed = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (_: SerializationException) {
            refused("arguments: not JSON")
        }
        val input = parsed as? JsonObject ?: refused("arguments: not an object")
        ScriptSchema.problem(tool.inputSchema, input)?.let { refused(it) }
        return input
    }

    private fun finish(tool: ManifestTool, result: ExecResult, seconds: Int): ToolCallResult {
        if (!result.ok) {
            val timedOut = result.errorCode == RuntimeErrorCode.EXEC_TIMEOUT.wireCode
            return failure(if (timedOut) "the script ran over its time ($seconds s)" else "the script failed: ${result.message.take(MAX_ERROR_CHARS)}")
        }
        val envelope = try {
            Json.parseToJsonElement(result.output) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return failure("the script's result could not be read")
        (envelope[KEY_LOGS] as? JsonArray)?.take(limits.logLines)?.forEach { line ->
            log("package ${grant.packageId}, tool ${tool.name}: ${(line as? JsonPrimitive)?.contentOrNull.orEmpty().take(limits.logChars)}")
        }
        if ((envelope[KEY_OK] as? JsonPrimitive)?.booleanOrNull != true) {
            return failure((envelope[KEY_ERROR] as? JsonPrimitive)?.contentOrNull.orEmpty().take(MAX_ERROR_CHARS).ifEmpty { "run failed" })
        }
        val value = envelope[KEY_VALUE] ?: JsonNull
        val text = value.toString()
        if (text.toByteArray(Charsets.UTF_8).size > limits.resultBytes) return failure("run returned over ${limits.resultBytes / KIB} KiB")
        return ToolCallResult(success = true, resultJson = if (value is JsonObject) text else JsonObject(mapOf(KEY_RESULT to value)).toString())
    }

    private fun failure(message: String): ToolCallResult =
        ToolCallResult(success = false, resultJson = JsonObject(mapOf(KEY_ERROR to JsonPrimitive(message))).toString())
}

/**
 * The code the engine runs for one call (inside the engine's own function, `"use strict"`): the host functions
 * (`host.http`, `host.tool`, `host.log`) over the engine's bridge, which is then taken from the script's reach with
 * the engine's other globals; the input; the script; and a call of `run(input)` whose return value comes back as
 * `{ok, logs, value}` and whose error as `{ok: false, logs, error}`. Nothing here is a limit: the script can change all
 * of it, so the host checks what it reads back ([ScriptRunner]) and what each host call asks ([ScriptBridge]).
 */
internal fun scriptCode(script: String, input: JsonObject, limits: ScriptLimits): String =
    SCRIPT_PRELUDE
        .replace("__LOG_LINES__", limits.logLines.toString())
        .replace("__LOG_CHARS__", limits.logChars.toString())
        .replace("__INPUT__", jsString(input.toString())) +
        "\n" + script + "\n" +
        SCRIPT_EPILOGUE
            .replace("__RESULT_CHARS__", limits.resultBytes.toString())
            .replace("__RESULT_KIB__", (limits.resultBytes / KIB).toString())

/** [text] as a JavaScript string literal (JSON's, with the two line separators JSON leaves bare escaped). */
private fun jsString(text: String): String = JsonPrimitive(text).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** Room in the engine's output for the result, the log lines escaped at worst, and the rest of the envelope. */
private fun outputTokens(limits: ScriptLimits): Int =
    (limits.resultBytes + limits.logLines * limits.logChars * JSON_ESCAPE_FACTOR + ENVELOPE_CHARS) / CHARS_PER_TOKEN + 1

internal class ScriptRefusal(override val message: String) : Exception(message)

internal fun refused(message: String): Nothing = throw ScriptRefusal(message)

private const val KEY_OK = "ok"
private const val KEY_LOGS = "logs"
private const val KEY_VALUE = "value"
private const val KEY_ERROR = "error"
private const val KEY_RESULT = "result"
private const val KIB = 1024
private const val MAX_ERROR_CHARS = 1000

/** As the engine counts its output budget. */
private const val CHARS_PER_TOKEN = 4

/** `\u0000` is six characters for one. */
private const val JSON_ESCAPE_FACTOR = 6
private const val ENVELOPE_CHARS = 1024

private val SCRIPT_PRELUDE = """
const __pkgCall = globalThis.tools.call;
delete globalThis.tools;
delete globalThis.emit;
const __pkgLogs = [];
function __pkgHost(name, request) {
  const answer = JSON.parse(__pkgCall(name, JSON.stringify(request === undefined ? null : request)));
  if (answer.error !== undefined) throw new Error(String(answer.error));
  return answer.value;
}
const host = Object.freeze({
  http: function (request) { return __pkgHost("host.http", request); },
  tool: function (name, args) { return __pkgHost("host.tool", { name: name, arguments: args === undefined ? {} : args }); },
  log: function (message) {
    if (__pkgLogs.length < __LOG_LINES__) __pkgLogs.push(String(message).slice(0, __LOG_CHARS__));
    return undefined;
  }
});
const __pkgInput = JSON.parse(__INPUT__);
""".trimIndent()

private val SCRIPT_EPILOGUE = """
return (function () {
  try {
    if (typeof run !== "function") throw new Error("the script defines no function run(input)");
    const value = run(__pkgInput);
    if (value !== null && (typeof value === "object" || typeof value === "function") && typeof value.then === "function") {
      throw new Error("run returned a promise: host calls answer at once, so run returns its value itself");
    }
    const text = JSON.stringify(value === undefined ? null : value);
    if (text !== undefined && text.length > __RESULT_CHARS__) throw new Error("run returned over __RESULT_KIB__ KiB");
    return '{"ok":true,"logs":' + JSON.stringify(__pkgLogs) + ',"value":' + (text === undefined ? "null" : text) + "}";
  } catch (e) {
    let message = "run failed";
    try { message = String(e); } catch (ignored) {}
    return JSON.stringify({ ok: false, logs: __pkgLogs, error: message.slice(0, 1000) });
  }
})();
""".trimIndent()
