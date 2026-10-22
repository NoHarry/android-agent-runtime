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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/** Zipline-backed JavaScript exec engine for the optional `exec_js` runtime tool. */
@OptIn(EngineApi::class)
class ZiplineExecEngine : ExecEngine {
    /** Explicit serialized override; QuickJS evaluation is not reentrant. */
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
        val hostResults = linkedMapOf<HostCall, String>()
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
                hostResults[result.hostCall] = bridge.callTool(result.hostCall.name, result.hostCall.argumentsJson)
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
        hostResults: Map<HostCall, String>,
    ): EvaluationResult =
        runInterruptible {
            QuickJs.create().use { quickJs ->
                quickJs.interruptHandler = InterruptHandler { System.nanoTime() > deadlineNanos }
                try {
                    val value = quickJs.evaluate(scriptFor(request.code, hostResults), EXEC_SOURCE_NAME)?.toString().orEmpty()
                    EvaluationResult.from(value)
                } catch (error: QuickJsException) {
                    if (System.nanoTime() > deadlineNanos) throw ExecTimeoutException()
                    val hostCall = HostCall.fromException(error)
                    if (hostCall != null) EvaluationResult(hostCall = hostCall) else throw error
                }
            }
        }

    private fun scriptFor(code: String, hostResults: Map<HostCall, String>): String =
        """
        "use strict";
        const __execOutput = [];
        const __execHostResults = ${hostResultsJson(hostResults)};
        globalThis.emit = function(text) {
          __execOutput.push(String(text ?? ""));
          return undefined;
        };
        globalThis.tools = Object.freeze({
          call: function(name, argumentsJson) {
            const normalizedName = String(name ?? "");
            const normalizedArguments = argumentsJson === undefined ? "{}" : String(argumentsJson);
            const key = normalizedName + "\u0000" + normalizedArguments;
            if (Object.prototype.hasOwnProperty.call(__execHostResults, key)) {
              return __execHostResults[key];
            }
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
          value: __execValue === undefined ? "" : String(__execValue)
        });
        """.trimIndent()

    private fun hostResultsJson(hostResults: Map<HostCall, String>): String =
        JsonObject(
            hostResults.mapKeys { (call, _) -> call.key }
                .mapValues { (_, result) -> JsonPrimitive(result) },
        ).toString()

    private data class EvaluationResult(
        val output: String = "",
        val value: String = "",
        val hostCall: HostCall? = null,
    ) {
        companion object {
            fun from(value: String): EvaluationResult {
                val json = Json.parseToJsonElement(value).jsonObject
                return EvaluationResult(
                    output = json[RESULT_OUTPUT]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    value = json[RESULT_VALUE]?.jsonPrimitive?.contentOrNull.orEmpty(),
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
                val json = Json.parseToJsonElement(payload).jsonObject
                return HostCall(
                    name = json[HOST_CALL_NAME]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argumentsJson = json[HOST_CALL_ARGUMENTS]?.jsonPrimitive?.contentOrNull ?: EMPTY_JSON,
                )
            }
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
private const val HOST_CALL_NAME = "name"
private const val HOST_CALL_ARGUMENTS = "argumentsJson"
private const val RESULT_OUTPUT = "output"
private const val RESULT_VALUE = "value"
