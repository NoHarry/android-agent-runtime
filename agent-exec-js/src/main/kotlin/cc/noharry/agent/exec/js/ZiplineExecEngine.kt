@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.exec.js

import app.cash.zipline.EngineApi
import app.cash.zipline.InterruptHandler
import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Zipline-backed JavaScript exec engine for the optional `exec_js` runtime tool.
 *
 * QuickJS cannot suspend a script while a tool runs, so a script's first unanswered `tools.call` throws, the engine
 * runs that tool, and the script runs again from the start with the answer. Answers are replayed by order: the n-th
 * call of every run gets the n-th answer, so a read after a write is read again and the same call made twice runs
 * twice. A run whose calls differ from the earlier runs (it depends on Math.random or the time) stops there, before
 * any tool runs again.
 */
@OptIn(EngineApi::class)
class ZiplineExecEngine : ExecEngine {
    /** Explicit serialized override; QuickJS evaluation is not reentrant. */
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
        val hostResults = mutableListOf<Pair<HostCall, String>>()
        return try {
            repeat(MAX_HOST_CALLS + 1) { attempt ->
                val result = evaluate(request, deadlineNanos, hostResults)
                if (result.hostCall == null) {
                    val output = OutputBudget(request.outputTokenBudget)
                    val emitted = output.append(result.output)
                    val finalOutput = emitted.ifBlank { output.clamp(result.value) }
                    if (finalOutput.isNotEmpty()) bridge.emitOutput(finalOutput)
                    return ExecResult(ok = true, output = finalOutput)
                }
                if (attempt >= MAX_HOST_CALLS) throw ExecBridgeException("Too many nested exec tool calls.")
                hostResults += result.hostCall to bridge.callTool(result.hostCall.name, result.hostCall.argumentsJson)
            }
            ExecResult(ok = false, errorCode = RuntimeErrorCode.EXEC_FAILED.wireCode, message = "JavaScript exec failed.")
        } catch (error: ExecTimeoutException) {
            ExecResult(ok = false, errorCode = RuntimeErrorCode.EXEC_TIMEOUT.wireCode, message = "Exec timed out.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: QuickJsException) {
            ExecResult(
                ok = false,
                errorCode = RuntimeErrorCode.EXEC_FAILED.wireCode,
                message = error.message ?: "JavaScript exec failed.",
            )
        } catch (error: ExecBridgeException) {
            ExecResult(
                ok = false,
                errorCode = RuntimeErrorCode.EXEC_FAILED.wireCode,
                message = error.message ?: "JavaScript exec failed.",
            )
        }
    }

    private suspend fun evaluate(
        request: ExecRequest,
        deadlineNanos: Long,
        hostResults: List<Pair<HostCall, String>>,
    ): EvaluationResult =
        runInterruptible {
            QuickJs.create().use { quickJs ->
                quickJs.interruptHandler = InterruptHandler { System.nanoTime() > deadlineNanos }
                try {
                    val value = quickJs.evaluate(scriptFor(request.code, hostResults), EXEC_SOURCE_NAME)?.toString().orEmpty()
                    EvaluationResult.from(value).also { if (it.mismatch) throw ExecBridgeException(REPLAY_MISMATCH_MESSAGE) }
                } catch (error: QuickJsException) {
                    if (System.nanoTime() > deadlineNanos) throw ExecTimeoutException()
                    if (error.message.orEmpty().contains(REPLAY_MISMATCH_PREFIX)) throw ExecBridgeException(REPLAY_MISMATCH_MESSAGE)
                    val hostCall = HostCall.fromException(error)
                    if (hostCall != null) EvaluationResult(hostCall = hostCall) else throw error
                }
            }
        }

    private fun scriptFor(code: String, hostResults: List<Pair<HostCall, String>>): String =
        """
        "use strict";
        const __execOutput = [];
        const __execHostResults = ${hostResultsJson(hostResults)};
        let __execCallIndex = 0;
        // Kept outside the thrown error too, so a script's try/catch around tools.call cannot swallow the request.
        let __execPending = null;
        let __execMismatch = false;
        globalThis.emit = function(text) {
          __execOutput.push(String(text ?? ""));
          return undefined;
        };
        globalThis.tools = Object.freeze({
          call: function(name, argumentsJson) {
            const normalizedName = String(name ?? "");
            const normalizedArguments = argumentsJson === undefined ? "{}" : String(argumentsJson);
            const key = normalizedName + "\u0000" + normalizedArguments;
            const index = __execCallIndex++;
            if (index < __execHostResults.length) {
              if (__execHostResults[index].key !== key) {
                __execMismatch = true;
                throw new Error("$REPLAY_MISMATCH_PREFIX" + index);
              }
              return __execHostResults[index].result;
            }
            __execPending = __execPending ?? { name: normalizedName, argumentsJson: normalizedArguments };
            throw new Error("$HOST_CALL_PREFIX" + JSON.stringify({
              name: normalizedName,
              argumentsJson: normalizedArguments
            }));
          }
        });
        const __execValue = (function() {
        $code
        })();
        JSON.stringify({
          output: __execOutput.join(""),
          value: __execValue === undefined ? "" : String(__execValue),
          pending: __execPending,
          mismatch: __execMismatch
        });
        """.trimIndent()

    private fun hostResultsJson(hostResults: List<Pair<HostCall, String>>): String =
        JsonArray(
            hostResults.map { (call, result) ->
                JsonObject(mapOf("key" to JsonPrimitive(call.key), "result" to JsonPrimitive(result)))
            },
        ).toString()

    private data class EvaluationResult(
        val output: String = "",
        val value: String = "",
        val hostCall: HostCall? = null,
        val mismatch: Boolean = false,
    ) {
        companion object {
            /** A script that caught the request to run a tool still gets that tool run; its return value waits. */
            fun from(value: String): EvaluationResult {
                val json = Json.parseToJsonElement(value).jsonObject
                val pending = json[RESULT_PENDING] as? JsonObject
                return EvaluationResult(
                    output = json[RESULT_OUTPUT]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    value = json[RESULT_VALUE]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    hostCall = pending?.let { HostCall.from(it) },
                    mismatch = json[RESULT_MISMATCH]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
        }
    }

    private data class HostCall(
        val name: String,
        val argumentsJson: String,
    ) {
        val key: String = "$name\u0000$argumentsJson"

        companion object {
            fun fromException(error: QuickJsException): HostCall? {
                val message = error.message.orEmpty()
                val payload = message.substringAfter(HOST_CALL_PREFIX, missingDelimiterValue = "")
                    .substringBefore('\n')
                    .ifBlank { return null }
                return from(Json.parseToJsonElement(payload).jsonObject)
            }

            fun from(json: JsonObject): HostCall = HostCall(
                name = json[HOST_CALL_NAME]?.jsonPrimitive?.contentOrNull.orEmpty(),
                argumentsJson = json[HOST_CALL_ARGUMENTS]?.jsonPrimitive?.contentOrNull ?: EMPTY_JSON,
            )
        }
    }
}

private class OutputBudget(outputTokenBudget: Int) {
    private val maxChars = (outputTokenBudget.coerceAtLeast(MIN_OUTPUT_TOKENS) * CHARS_PER_TOKEN)
    private val builder = StringBuilder()

    fun append(text: String): String {
        val chunk = clamp(text)
        builder.append(chunk)
        return chunk
    }

    fun clamp(text: String): String {
        val remaining = maxChars - builder.length
        return text.take(remaining.coerceAtLeast(0))
    }

    fun result(): String = builder.toString()
}

private class ExecTimeoutException : RuntimeException("Exec timed out.")
private class ExecBridgeException(message: String) : RuntimeException(message)

private const val EXEC_SOURCE_NAME = "exec_js"
private const val EMPTY_JSON = "{}"
private const val MIN_OUTPUT_TOKENS = 1
private const val CHARS_PER_TOKEN = 4
private const val MAX_HOST_CALLS = 32
private const val HOST_CALL_PREFIX = "__AGENT_EXEC_HOST_CALL__"
private const val REPLAY_MISMATCH_PREFIX = "__AGENT_EXEC_REPLAY_MISMATCH__"
private const val REPLAY_MISMATCH_MESSAGE =
    "The script called tools differently when it ran again (a call depended on Math.random or the time); " +
        "no tool ran a second time. Compute such values once, outside the tool calls."
private const val HOST_CALL_NAME = "name"
private const val HOST_CALL_ARGUMENTS = "argumentsJson"
private const val RESULT_OUTPUT = "output"
private const val RESULT_VALUE = "value"
private const val RESULT_PENDING = "pending"
private const val RESULT_MISMATCH = "mismatch"
