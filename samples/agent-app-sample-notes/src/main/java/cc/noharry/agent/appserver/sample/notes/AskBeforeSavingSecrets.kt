package cc.noharry.agent.appserver.sample.notes

// guide:start hook
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolUse
import org.json.JSONObject

/**
 * A note that looks like a password or a code waits for the person's yes, although saving notes needs none
 * (`unaskedTools`): a hook's own rule adds to the server's. A hook that answers [HookDecision.RequireConfirmation] makes
 * any call ask, and the approval mode then decides as for every call that asks; one that answers [HookDecision.Deny]
 * refuses it in every mode. After a yes the same call comes back with `approvedConfirmation` set.
 */
object AskBeforeSavingSecrets : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (call.toolName != "mcp__notes__add_note" || context.approvedConfirmation) return HookDecision.Allow()
        val text = runCatching { JSONObject(call.argumentsJson).optString("text") }.getOrDefault("")
        return if (SECRET.containsMatchIn(text)) {
            HookDecision.RequireConfirmation(reason = "LOOKS_SECRET", prompt = "Save a note that looks like a password?")
        } else {
            HookDecision.Allow()
        }
    }

    private val SECRET = Regex("""password|passcode|\bpin\b|\b\d{6,}\b""", RegexOption.IGNORE_CASE)
}
// guide:end hook
