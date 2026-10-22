@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.CHECKPOINT_PREAMBLE
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactionRuntimeTest {
    @Test
    fun aRunStartingNearTheWindowCompactsTheSessionsOldestRunsFirst() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(ModelEvent.UsageDelta(inputTokens = 1000, outputTokens = 5), finish("一")),
                round(ModelEvent.UsageDelta(inputTokens = 1900, outputTokens = 5), finish("二")),
                round(ModelEvent.TextDelta("## Requests and intent\n- 空调")),
                round(finish("三")),
            ),
        )
        val history = SessionHistory()
        suspend fun run(id: String) = AgentRuntimeBuilder.create()
            .modelClient(model)
            .sessionHistory(history)
            .contextWindow(2000)
            .build()
            .run(AgentRequest(id, "session-1", "空调".repeat(2000) + id))
            .toList()

        run("request-1")
        run("request-2")
        run("request-3")

        // Run 2 ended at 1900 input tokens, over 80% of the window: run 3's summary request came before its own.
        val summaryRequest = model.seenRequests[2]
        assertTrue(summaryRequest.messages.last().content.startsWith("You are now compacting this conversation."))
        val firstOfRun3 = model.seenRequests[3].messages
        assertTrue(firstOfRun3.first().content.startsWith(CHECKPOINT_PREAMBLE))
        assertEquals("空调".repeat(2000) + "request-3", firstOfRun3[1].content)
        assertEquals(1, history.messages("session-1").count { it.content.startsWith(CHECKPOINT_PREAMBLE) })
    }
}
