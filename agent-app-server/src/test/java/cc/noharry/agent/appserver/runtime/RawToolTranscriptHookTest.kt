package cc.noharry.agent.appserver.runtime
import org.junit.Assert.*
import org.junit.Test

class RawToolTranscriptHookTest {
    @Test fun hostCardRedactionKeepsPhoneAndTrackingNumbersButHidesCredentials() {
        val result = redactToolPayload("""{"tracking_no":"SF1234567890123","phone":"13912345678","token":"private-value","nested":{"api_key":"private-key"}}""")
        assertTrue(result.contains("SF1234567890123"))
        assertTrue(result.contains("13912345678"))
        assertFalse(result.contains("private-value"))
        assertFalse(result.contains("private-key"))
    }

    @Test fun malformedJsonStillHidesCredentials() {
        val result = redactToolPayload("""{"token":"private-value","nested":{"api_key":"private-key"""")
        assertFalse(result.contains("private-value"))
        assertFalse(result.contains("private-key"))
    }
}
