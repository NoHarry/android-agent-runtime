package cc.noharry.agent.appserver.sample.notes

// guide:start tools
import android.content.Context
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's own tools: notes kept as lines of a file in the app's storage. The model reaches them as
 * `mcp__notes__add_note`, `mcp__notes__list_notes` and `mcp__notes__clear_notes`. The two that change the notes say so
 * (`MUTATING`) and so ask before they run, unless the config names them; the one that only reads says so (`READ_ONLY`)
 * and never asks.
 */
class NotesTools(context: Context) : ToolProvider {
    private val file = File(context.filesDir, "notes.txt")

    override val providerId: String = "notes"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "add_note",
            description = "Save one note for the user.",
            inputSchemaJson = """{"type":"object","properties":{"text":{"type":"string","description":"The note."}},"required":["text"]}""",
            effectKind = "MUTATING",
        ),
        ToolDescriptor(
            name = "list_notes",
            description = "List every saved note, oldest first.",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            effectKind = "READ_ONLY",
        ),
        ToolDescriptor(
            name = "clear_notes",
            description = "Delete every saved note. The user is asked first.",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            effectKind = "MUTATING",
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult = when (toolName) {
        "add_note" -> {
            val text = JSONObject(argumentsJson).optString("text").trim()
            if (text.isEmpty()) {
                failed("text is required")
            } else {
                synchronized(file) { file.appendText(text.replace('\n', ' ') + "\n") }
                ToolCallResult(success = true, resultJson = JSONObject().put("saved", text).toString())
            }
        }
        "list_notes" -> ToolCallResult(success = true, resultJson = JSONObject().put("notes", JSONArray(notes())).toString())
        "clear_notes" -> {
            val cleared = synchronized(file) { notes().size.also { file.delete() } }
            ToolCallResult(success = true, resultJson = JSONObject().put("cleared", cleared).toString())
        }
        else -> failed("unknown tool $toolName")
    }

    private fun notes(): List<String> = synchronized(file) { if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList() }

    private fun failed(message: String) = ToolCallResult(success = false, resultJson = JSONObject().put("error", message).toString())
}
// guide:end tools
