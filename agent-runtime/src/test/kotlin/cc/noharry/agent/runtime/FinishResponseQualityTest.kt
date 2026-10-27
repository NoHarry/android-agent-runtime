package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.loop.completed
import cc.noharry.agent.kernel.core.loop.isPureNarrationFinishResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishResponseQualityTest {
    @Test
    fun rejectsInternalPlanEvenWhenItMentionsConcreteResults() {
        assertTrue(isPureNarrationFinishResponse(
            "All done. Now finish with a concise Chinese report covering 广州塔: 329.9 kilometers, music played.",
        ))
        assertTrue(isPureNarrationFinishResponse("已根据可用工具完成请求。"))
        assertFalse(isPureNarrationFinishResponse("导航已开启，全程剩余329.9公里；添加途经点失败，当前路线未改变。"))
    }

    @Test
    fun acceptsKnownGoodBenchResponses() {
        knownGoodResponses.forEach { response ->
            assertFalse(response, isPureNarrationFinishResponse(response))
        }
    }

    @Test
    fun rejectsKnownPureNarrationBenchResponses() {
        knownPureNarrationResponses.forEach { response ->
            assertTrue(response, isPureNarrationFinishResponse(response))
        }
    }

    @Test
    fun acceptsMixedNarrationWhenConcreteResultsArePresent() {
        mixedResponses.forEach { response ->
            assertFalse(response, isPureNarrationFinishResponse(response))
        }
    }
}

private val knownGoodResponses = listOf(
    "我是小助，名字里的博代表博学和博览。我已为你查到广州塔停车、深圳北站附近肯德基，" +
        "规划了不走高速路线，沿途包含厕所点位，导航已开启并播放周杰伦的歌。",
    "我是小助，这个名字寓意快速响应你的设备任务。已找到广州塔停车场，途经深圳北站肯德基，" +
        "路线避开高速，并查好洗手间，开始导航后播放周杰伦音乐。",
    "我是小助，这个名字代表 Hermes 式的信息传递能力。已规划广州塔路线，查到停车、KFC、厕所，" +
        "路线不走高速，导航和周杰伦歌曲都已处理。",
    "我是小助，名字含义是陪你抵达目的地。已完成广州塔停车搜索、深圳北站肯德基、避高速路线、" +
        "沿途卫生间、导航开启和周杰伦播放。",
    "我是小助，名字代表智能出行助手。广州塔停车、深圳北站附近肯德基、不走高速路线、沿途厕所、" +
        "导航和周杰伦歌曲都已经为你安排好。",
)

private val knownPureNarrationResponses = listOf(
    "Good, all actions are done. Let me now finish. I planned the route and found parking.",
    "All actions completed successfully. Let me now wrap up with the intro and final response, updating the todo list to completed.",
    "All steps completed successfully! Let me update the todo list and provide the final summary.",
    "All tasks completed! Let me update the todo and provide the final response.",
    "Now let me update the todo list and finish with a comprehensive response.",
    "All steps completed successfully. Let me update the todo and finish with a comprehensive response.",
    "All operations are complete. Let me now update the todo list and finish with a complete summary.",
    "(same reasoning as previous tool call)",
)

private val mixedResponses = listOf(
    "Let me summarize the result: 已找到目的地停车场，已规划避让路线，并已播放所选音乐。",
    "All tasks are complete; final answer: I created the itinerary, saved the stop, and set to the requested volume.",
)
