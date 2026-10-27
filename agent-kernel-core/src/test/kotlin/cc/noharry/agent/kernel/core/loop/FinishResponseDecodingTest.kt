package cc.noharry.agent.kernel.core.loop
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishResponseDecodingTest {
    @Test fun decodesEscapesAndIgnoresNestedAndNonStringFields() {
        assertEquals("一\n二\t\"quoted\"\\path", extractFinishResponse("""{"response":"\u4e00\n二\t\"quoted\"\\path"}"""))
        assertEquals("", extractFinishResponse("""{"nested":{"response":"wrong"}}"""))
        assertEquals("", extractFinishResponse("""{"response":123}"""))
        assertEquals("", extractFinishResponse("""{"response":null}"""))
        assertEquals("", extractFinishResponse("broken"))
        assertEquals(null, extractFinishOutcomeToken("""{"outcome":{}}"""))
    }
}
