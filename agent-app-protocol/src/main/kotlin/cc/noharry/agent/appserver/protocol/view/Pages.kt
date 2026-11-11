package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The tool that draws a page in the conversation: the model writes it as HTML, CSS and script. */
const val SHOW_HTML_TOOL = "show_html"

/** Where the server keeps drawn pages, one folder per session, under its files. */
const val DRAWN_PAGES_DIR = "drawn-pages"

/** A drawn page as the conversation shows it: what to read it by ([reference]) and its [title]. */
data class ShownPage(val reference: String, val title: String)

/** A page this call drew, once it is done; null for other calls, a failed one, or a result that lost its reference. */
fun ConversationItem.ToolCallItem.shownPage(): ShownPage? {
    if (status != ToolCallStatus.DONE || !isShowHtml()) return null
    val result = resultJson ?: return null
    // Public results are plain JSON; the raw hook's row wraps the same object in a `result={...}` line.
    val payload = parseJsonObject(result) ?: result.lineValue("result")?.let(::parseJsonObject) ?: return null
    val reference = payload["page"]?.jsonPrimitive?.contentOrNull?.takeIf { DRAWN_PAGE.matches(it) } ?: return null
    return ShownPage(reference, payload["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
}

/** Whether this call is `show_html`, bare or as the server qualifies its own tools (`mcp__pages__show_html`). */
fun ConversationItem.ToolCallItem.isShowHtml(): Boolean = toolName == SHOW_HTML_TOOL || toolName.endsWith("__$SHOW_HTML_TOOL")

/** A page reference: the session's folder and the page's file, nothing that could leave the pages folder. */
val DRAWN_PAGE = Regex("$DRAWN_PAGES_DIR/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.html")
