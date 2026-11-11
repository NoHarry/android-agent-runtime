@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.spi.SessionJson
import cc.noharry.agent.appserver.runtime.append

internal fun SessionJson.render(pretty: Boolean = true): String = renderTo(StringBuilder(), 0, pretty).toString()

private fun SessionJson.renderTo(out: StringBuilder, indent: Int, pretty: Boolean): StringBuilder {
    when (this) {
        SessionJson.Null -> out.append("null")
        is SessionJson.Bool -> out.append(value)
        is SessionJson.Number -> out.append(literal)
        is SessionJson.Text -> out.append('"').append(value.escapeJson()).append('"')
        is SessionJson.Array -> {
            if (values.isEmpty()) {
                out.append("[]")
            } else {
                out.append('[')
                values.forEachIndexed { index, value ->
                    if (pretty) out.append('\n').append("  ".repeat(indent + 1))
                    value.renderTo(out, indent + 1, pretty)
                    if (index != values.lastIndex) out.append(',')
                }
                if (pretty) out.append('\n').append("  ".repeat(indent))
                out.append(']')
            }
        }
        is SessionJson.Object -> {
            if (values.isEmpty()) {
                out.append("{}")
            } else {
                out.append('{')
                values.entries.forEachIndexed { index, (key, value) ->
                    if (pretty) out.append('\n').append("  ".repeat(indent + 1))
                    out.append('"').append(key.escapeJson()).append('"').append(if (pretty) ": " else ":")
                    value.renderTo(out, indent + 1, pretty)
                    if (index != values.size - 1) out.append(',')
                }
                if (pretty) out.append('\n').append("  ".repeat(indent))
                out.append('}')
            }
        }
    }
    return out
}

private fun String.escapeJson(): String {
    val source = this
    return buildString {
        source.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
