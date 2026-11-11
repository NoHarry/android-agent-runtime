package cc.noharry.agent.appserver.sample.notes

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSelectParams
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState

/** A model service the user connects: an OpenAI-compatible chat endpoint, its key and the model's name. */
class ModelChoice(val baseUrl: String, val model: String, val apiKey: String)

// guide:start use-model
/**
 * Has the server answer with [choice] from now on: a profile of the person's own, which wins over the one the build
 * carries, also after the app restarts.
 */
suspend fun ServerClient.use(choice: ModelChoice) {
    val created = request(
        SettingsRequests.PROVIDER_CREATE,
        ProviderCreateParams("Notes model", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, choice.apiKey, choice.model, choice.baseUrl),
    )
    request(SettingsRequests.MODEL_SELECT, ModelSelectParams(profileId = created.profileId))
}
// guide:end use-model

// guide:start transcript
/** The current thread as text: the person's words, the answers and what went wrong. */
fun ServerState.transcript(): String = items.mapNotNull { item ->
    when (item) {
        is ConversationItem.TurnHeader -> "> ${item.fullText}"
        is ConversationItem.MessageItem -> when (item.role) {
            TranscriptRole.ASSISTANT -> item.content.takeIf { item.final }
            TranscriptRole.ERROR -> "! ${item.content}"
            else -> null
        }
        else -> null
    }
}.joinToString("\n\n")

/** The last turn's answer, or what went wrong. */
fun ServerState.lastAnswer(): String? = items.asReversed().firstNotNullOfOrNull { item ->
    (item as? ConversationItem.MessageItem)?.takeIf { it.role == TranscriptRole.ERROR || (it.role == TranscriptRole.ASSISTANT && it.final) }?.content
}
// guide:end transcript
