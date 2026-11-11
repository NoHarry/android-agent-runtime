@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * A small model sometimes writes a tool call as reply text instead of making it: Gemma 4 E4B ended runs with
 * `finish(response="客厅的温度已经设置到25度了。", outcome="success")`, on its own line or straight after its spoken
 * line (「我已经把空调调到25度了。finish(…)」), and the text became the reply, read out as 「finish」. Text from
 * one of [toolNames] and `(`, where a word starts, is held to the end of the response; when it parses as such calls
 * (keyword arguments with literal values) they go on as tool calls, else as the text they were. Other text passes at
 * once, except a word start that could still become a call.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun Flow<ModelEvent>.withTextToolCalls(
    toolNames: Set<String>,
    newId: () -> String = { "text-call-${UUID.randomUUID()}" },
): Flow<ModelEvent> = flow {
    val scanner = WrittenCallScanner(toolNames)

    suspend fun flush() {
        val written = scanner.takeCall()
        if (written != null) {
            val calls = parseWrittenCalls(written, toolNames)
            if (calls == null) emit(ModelEvent.TextDelta(written))
            else calls.forEach { (name, arguments) -> emit(ModelEvent.ToolCall(newId(), name, arguments.toString())) }
        }
        val rest = scanner.takePending()
        if (rest.isNotEmpty()) emit(ModelEvent.TextDelta(rest))
    }

    collect { event ->
        when (event) {
            is ModelEvent.TextDelta -> scanner.pass(event.text).takeIf(String::isNotEmpty)?.let { emit(ModelEvent.TextDelta(it)) }
            ModelEvent.Completed -> {
                flush()
                emit(event)
            }
            else -> emit(event)
        }
    }
    flush()
}

/** Streamed text: what can go on at once, and a written call held from its tool's name to the end of the response. */
private class WrittenCallScanner(private val toolNames: Set<String>) {
    /** Text that could still be the start of a call. */
    private val pending = StringBuilder()
    private var held: StringBuilder? = null
    private var lastPassed = '\n'

    /** The part of [text] that can go on now. */
    fun pass(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            val call = held
            if (call != null) call.append(ch) else scan(ch, out)
        }
        return out.toString()
    }

    /** The written call held so far, if there is one. */
    fun takeCall(): String? = held?.toString().also { held = null }

    /** A word start still held because it could have become a call. */
    fun takePending(): String = pending.toString().also {
        pending.clear()
        lastPassed = '\n'
    }

    private fun scan(ch: Char, out: StringBuilder) {
        pending.append(ch)
        val start = pending.indices.firstOrNull { wordStartsAt(it) && mayBeCall(it) } ?: pending.length
        if (start > 0) {
            out.append(pending, 0, start)
            lastPassed = pending[start - 1]
            pending.delete(0, start)
        }
        if (toolNames.any { pending.startsWith("$it(") }) held = StringBuilder(pending).also { pending.clear() }
    }

    private fun wordStartsAt(index: Int): Boolean = !(if (index == 0) lastPassed else pending[index - 1]).isNamePart()

    private fun mayBeCall(from: Int): Boolean {
        val rest = pending.substring(from)
        return toolNames.any { "$it(".startsWith(rest) }
    }
}

private fun Char.isNamePart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/** `name(key=value, …)` calls, one after another, each naming one of [toolNames]; null when the text is anything else. */
internal fun parseWrittenCalls(text: String, toolNames: Set<String>): List<Pair<String, JsonObject>>? =
    WrittenCallParser(text).calls(toolNames)

/** Python-style keyword calls whose values are literals: strings, numbers, booleans, null, lists and dicts. */
private class WrittenCallParser(
    private val s: String,
) {
    private var i = 0

    fun calls(toolNames: Set<String>): List<Pair<String, JsonObject>>? {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        while (true) {
            skipSpace()
            if (i == s.length) return calls.takeIf { it.isNotEmpty() }
            val name = identifier()?.takeIf { it in toolNames } ?: return null
            if (!take('(')) return null
            calls += name to (arguments() ?: return null)
            skipSpace()
            take(';')
        }
    }

    private fun arguments(): JsonObject? {
        val arguments = linkedMapOf<String, JsonElement>()
        while (true) {
            skipSpace()
            if (take(')')) return JsonObject(arguments)
            val key = identifier() ?: return null
            skipSpace()
            if (!take('=')) return null
            arguments[key] = value() ?: return null
            skipSpace()
            if (!take(',') && s.getOrNull(i) != ')') return null
        }
    }

    private fun value(): JsonElement? {
        skipSpace()
        return when (s.getOrNull(i)) {
            '"', '\'' -> string()?.let(::JsonPrimitive)
            '[' -> list()
            '{' -> dict()
            else -> bare()
        }
    }

    private fun list(): JsonElement? {
        i++
        val items = mutableListOf<JsonElement>()
        while (true) {
            skipSpace()
            if (take(']')) return JsonArray(items)
            items += value() ?: return null
            skipSpace()
            if (!take(',') && s.getOrNull(i) != ']') return null
        }
    }

    private fun dict(): JsonElement? {
        i++
        val entries = linkedMapOf<String, JsonElement>()
        while (true) {
            skipSpace()
            if (take('}')) return JsonObject(entries)
            val key = when (s.getOrNull(i)) {
                '"', '\'' -> string()
                else -> identifier()
            } ?: return null
            skipSpace()
            if (!take(':')) return null
            entries[key] = value() ?: return null
            skipSpace()
            if (!take(',') && s.getOrNull(i) != '}') return null
        }
    }

    private fun string(): String? {
        val quote = s[i++]
        val out = StringBuilder()
        while (i < s.length) {
            val ch = s[i++]
            when {
                ch == quote -> return out.toString()
                ch != '\\' -> out.append(ch)
                i == s.length -> return null
                else -> when (val escaped = s[i++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        val code = s.substring(i, minOf(i + 4, s.length)).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> out.append(escaped)
                }
            }
        }
        return null
    }

    private fun bare(): JsonElement? {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-._")) i++
        return when (val word = s.substring(start, i)) {
            "true", "True" -> JsonPrimitive(true)
            "false", "False" -> JsonPrimitive(false)
            "null", "None" -> JsonNull
            else -> word.toLongOrNull()?.let(::JsonPrimitive) ?: word.toDoubleOrNull()?.let(::JsonPrimitive)
        }
    }

    private fun identifier(): String? {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(start, i).takeIf { it.isNotEmpty() && !it[0].isDigit() }
    }

    private fun take(ch: Char): Boolean = (s.getOrNull(i) == ch).also { if (it) i++ }

    private fun skipSpace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
