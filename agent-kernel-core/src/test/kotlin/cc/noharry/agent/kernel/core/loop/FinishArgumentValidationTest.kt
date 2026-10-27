package cc.noharry.agent.kernel.core.loop
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishArgumentValidationTest {
    @Test fun rejectsInvalidShapeAndOutcomeInsteadOfDefaultingToSuccess() {
        listOf("broken", "[]", "{}", """{"response":23}""", """{"response":" "}""",
            """{"response":"done","outcome":null}""", """{"response":"done","outcome":true}""",
            """{"response":"done","outcome":"unknown"}""", """{"response":"done","extra":1}""",
        ).forEach { assertTrue(it, invalidFinishArguments(it)) }
    }

    @Test fun rejectsUnquotedProtocolLeakButAcceptsCodeExamples() {
        val spill = "已算路，沿途搜索失败。</response>\n<parameter name=\"outcome\">partial"
        assertTrue(invalidFinishArguments(buildJsonObject { put("response", spill) }.toString()))
        assertFalse(invalidFinishResponseText("参数示例：`<parameter name=\"outcome\">partial</parameter>`。"))
        assertFalse(invalidFinishResponseText("参数示例：\n```xml\n$spill\n```"))
        assertFalse(invalidFinishResponseText("XML示例：<response>内容</response>"))
    }

    @Test fun acceptsAllOutcomesAndWellFormedLegacyCalls() {
        assertFalse(invalidFinishArguments("""{"response":"done"}"""))
        listOf("success", "partial", "blocked").forEach {
            assertFalse(invalidFinishArguments("""{"response":"实际结果","outcome":"$it"}"""))
        }
    }

    @Test fun nestedFinishJsonIsRejectedWhileOtherJsonAndQuotedExamplesAreAllowed() {
        val nested = """{"response":"未完成","outcome":"blocked"}"""
        assertTrue(invalidFinishArguments(buildJsonObject { put("response", nested) }.toString()))
        assertTrue(invalidFinishResponseText(nested))
        assertFalse(invalidFinishResponseText("```json\n$nested\n```"))
        assertFalse(invalidFinishResponseText("""{"temperature":20,"unit":"C"}"""))
    }
}
