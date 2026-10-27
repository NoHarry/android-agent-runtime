package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryRetrieverTest {
    @Test
    fun unrelatedOrQueryOnlyLessonReturnsNoSelection() {
        val retriever = RuntimeMemoryRetriever()
        val lessons = listOf(
            lesson("query-hvac", "Observed capability set: ac set temperature", RuntimeLessonMode.QUERY_ONLY),
            lesson("prewarm-nav", "Observed capability set: navi key word search"),
            lesson("unknown-domain", "Observed capability set: calculate tax"),
        )

        assertTrue(retriever.retrieve("有点热", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("现在几点", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("calculate tax", lessons).lessons.isEmpty())
    }

    @Test
    fun relevantPrewarmLessonIsSelectedForChineseRequest() {
        val selection = RuntimeMemoryRetriever().retrieve(
            requestText = "有点热",
            lessons = listOf(
                lesson("nav", "Observed capability set: navi key word search", confidence = 1f),
                lesson("hvac", "Observed capability set: ac set temperature", confidence = 0.8f),
            ),
        )

        assertEquals(listOf("hvac"), selection.lessons.map { it.id })
        assertEquals(listOf("ac set temperature"), selection.capabilityQueries)
    }

    @Test
    fun retrievalIsBoundedAndAvoidsDuplicateDomainLessons() {
        val selection = RuntimeMemoryRetriever().retrieve(
            requestText = "导航并播放音乐",
            lessons = listOf(
                lesson("nav-1", "Observed capability set: navi key word search", confidence = 0.8f),
                lesson("nav-2", "Observed capability set: navi calculate route", confidence = 1f),
                lesson("media", "Observed capability set: music play search music"),
            ),
        )

        assertEquals(2, selection.lessons.size)
        assertEquals(1, selection.lessons.count { it.id.startsWith("nav-") })
        assertTrue(selection.lessons.any { it.id == "media" })
    }

    @Test
    fun mixedDomainLessonOnlyPrewarmsQueryRelevantToRequest() {
        val selection = RuntimeMemoryRetriever().retrieve(
            requestText = "导航去公司",
            lessons = listOf(
                lesson(
                    "mixed",
                    "Observed capability set: navi key word search; music play search music",
                ),
            ),
        )

        assertEquals(listOf("mixed"), selection.lessons.map { it.id })
        assertEquals(listOf("navi key word search"), selection.capabilityQueries)
    }

    @Test
    fun navigationRequestKeepsRelevantQueriesFromBroaderLesson() {
        val selection = RuntimeMemoryRetriever().retrieve(
            requestText = "navigate to the tower",
            lessons = listOf(
                lesson(
                    "route",
                    "Capability search hints: destination POI, route preference, music playback, command_navi_KeyWordSearch",
                ),
            ),
        )

        assertEquals(listOf("navi key word search"), selection.capabilityQueries)
    }

    @Test
    fun ambiguousChineseCompoundsDoNotTriggerCrossDomainPrewarm() {
        val retriever = RuntimeMemoryRetriever()
        val lessons = listOf(
            lesson("hvac", "Observed capability set: ac set temperature"),
            lesson("nav", "Observed capability set: navi calculate route"),
            lesson("web", "Observed capability set: web fetch url"),
            lesson("media", "Observed capability set: music play search music"),
        )

        assertTrue(retriever.retrieve("讲个冷笑话", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("评审技术路线", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("解释神经网络", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("温度计使用说明", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("这个服务收费吗", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("暂停当前任务", lessons).lessons.isEmpty())
        assertTrue(retriever.retrieve("项目联系人是谁", lessons).lessons.isEmpty())
        assertEquals(
            listOf("media"),
            retriever.retrieve("播放热门音乐", lessons).lessons.map { it.id },
        )
        assertEquals(
            listOf("media"),
            retriever.retrieve("暂停播放", lessons).lessons.map { it.id },
        )
        assertEquals(
            listOf("nav"),
            retriever.retrieve("高速收费", lessons).lessons.map { it.id },
        )
    }

    private fun lesson(
        id: String,
        content: String,
        mode: RuntimeLessonMode = RuntimeLessonMode.PREWARM_ELIGIBLE,
        confidence: Float = 0.9f,
    ): RuntimeLesson = RuntimeLesson(
        id = id,
        scene = "debug-console",
        type = RuntimeCandidateType.MEMORY,
        content = content,
        confidence = confidence,
        hitCount = 3,
        createdAtEpochMillis = 1L,
        lastValidatedAtEpochMillis = 2L,
        mode = mode,
        evidenceRunKeys = setOf("run-a", "run-b", "run-c"),
    )
}
