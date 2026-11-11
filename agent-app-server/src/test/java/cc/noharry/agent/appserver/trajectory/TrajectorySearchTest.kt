package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRichDetails
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.view.matchesTrajectorySearch
import cc.noharry.agent.appserver.protocol.view.nextTrajectoryHit
import cc.noharry.agent.appserver.protocol.view.searchIndexText
import cc.noharry.agent.appserver.protocol.view.trajectorySearchHits
import cc.noharry.agent.appserver.protocol.view.trajectorySearchTokens
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectorySearchTest {

    @Test
    fun tokensUseAndSemanticsAcrossTitleSummaryAndMetadata() {
        val record = record(
            title = "web_search",
            summary = "status=COMPLETED",
            metadata = mapOf("source" to "BACKEND"),
        )

        assertTrue(record.matchesTrajectorySearch(trajectorySearchTokens("web_search completed")))
        assertTrue(record.matchesTrajectorySearch(trajectorySearchTokens("backend web")))
        assertFalse(record.matchesTrajectorySearch(trajectorySearchTokens("web_search missing")))
    }

    @Test
    fun matchingIsCaseInsensitiveAndLocaleStable() {
        val record = record(title = "TITLE", summary = "İstanbul")

        assertTrue(record.matchesTrajectorySearch(trajectorySearchTokens("title")))
        assertTrue(record.matchesTrajectorySearch(trajectorySearchTokens("TITLE")))
    }

    @Test
    fun blankQueryMatchesEverythingWithoutProducingHits() {
        val groups = listOf(group(listOf(record(title = "a"), record(title = "b"))))

        assertTrue(record(title = "a").matchesTrajectorySearch(trajectorySearchTokens("   ")))
        assertTrue(trajectorySearchHits(groups, "").isEmpty())
    }

    @Test
    fun indexOnlyCoversDisplaySafeFieldsNotHashes() {
        val record = record(
            title = "tool",
            summary = "ok",
            hashes = mapOf("arguments" to "deadbeefsecret"),
        )

        assertFalse(record.searchIndexText().contains("deadbeefsecret"))
        assertFalse(record.matchesTrajectorySearch(trajectorySearchTokens("deadbeefsecret")))
    }

    @Test
    @OptIn(ExperimentalAgentRuntimeApi::class)
    fun redactedRichPreviewsAreSearchableBecauseTheUiAlreadyShowsThem() {
        val record = record(
            title = "tool",
            rich = TrajectoryRichDetails(payloadPreview = "query=guangzhou weather"),
        )

        assertTrue(record.matchesTrajectorySearch(trajectorySearchTokens("guangzhou")))
    }

    @Test
    fun hitsFollowLedgerOrderAndStepCyclically() {
        val first = record(id = "r1", title = "alpha match")
        val second = record(id = "r2", title = "beta")
        val third = record(id = "r3", title = "gamma match")
        val groups = listOf(group(listOf(first, second, third)))

        val hits = trajectorySearchHits(groups, "match")

        assertEquals(listOf(first.id, third.id), hits)
        assertEquals(first.id, nextTrajectoryHit(hits, null, forward = true))
        assertEquals(third.id, nextTrajectoryHit(hits, first.id, forward = true))
        assertEquals(first.id, nextTrajectoryHit(hits, third.id, forward = true))
        assertEquals(third.id, nextTrajectoryHit(hits, first.id, forward = false))
        assertNull(nextTrajectoryHit(emptyList(), null))
    }

    private fun record(
        id: String = "r1",
        title: String = "title",
        summary: String = "",
        metadata: Map<String, String> = emptyMap(),
        hashes: Map<String, String> = emptyMap(),
        rich: TrajectoryRichDetails? = null,
    ) = TrajectoryRecord(
        id = TrajectoryRecordId(id),
        sessionId = "s1",
        runId = "run-1",
        kind = TrajectoryKind.TOOL_START,
        lane = TrajectoryLane.TOOLS,
        title = title,
        summary = summary,
        safeMetadata = metadata,
        contentHashes = hashes,
        rich = rich,
    )

    private fun group(records: List<TrajectoryRecord>) =
        TrajectoryRunGroup(sessionId = "s1", runId = "run-1", records = records, recordsLoaded = true)
}
