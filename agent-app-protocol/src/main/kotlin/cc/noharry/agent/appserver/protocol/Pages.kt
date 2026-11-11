package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** A page the agent drew in the conversation, by the reference its `show_html` call returned. */
@Serializable
data class PageReadParams(val reference: String)

/** The page's HTML, or null when it is gone: its session was deleted, or newer pages pushed it out. */
@Serializable
data class PageReadResult(val html: String? = null)

/**
 * Pages the agent drew (`show_html`): kept by the server and read when a front end shows one, so a page that may run
 * to hundreds of kilobytes does not ride every change of the conversation.
 */
object PageRequests {
    val READ = RequestMethod("pages/read", PageReadParams.serializer(), PageReadResult.serializer())
}
