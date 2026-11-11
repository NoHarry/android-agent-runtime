package cc.noharry.agent.appserver.sample.notes

// guide:start plugin
import android.content.Context
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import org.json.JSONObject

/**
 * Counting the notes' words, as a plugin: unlike the config's own tools, the person can turn it off and on while the
 * app runs (`plugins/set {"id":"note-stats","enabled":false}`), and everything [apply] added goes with it.
 */
object NoteStatsPlugin : AppServerPlugin {
    override val id: String = "note-stats"

    override fun apply(ctx: PluginContext) {
        ctx.tools { context, _ -> listOf(NoteStats(context)) }
        ctx.rules(listOf("When asked how much is noted, count the words with the note statistics tool."))
    }
}

private class NoteStats(context: Context) : ToolProvider {
    private val file = File(context.filesDir, "notes.txt")

    override val providerId: String = "note_stats"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor("count_words", "Count the words in all saved notes.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val words = if (file.exists()) file.readText().split(Regex("""\s+""")).count { it.isNotBlank() } else 0
        return ToolCallResult(success = true, resultJson = JSONObject().put("words", words).toString())
    }
}
// guide:end plugin
