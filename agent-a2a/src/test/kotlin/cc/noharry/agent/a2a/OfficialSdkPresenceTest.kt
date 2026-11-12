package cc.noharry.agent.a2a

import cc.noharry.agent.a2a.official.OfficialA2aClientModule
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The official client where the app has the official A2A Java SDK, and where it has not: `testWithoutOfficialSdk` runs
 * this, and every other test of the module but the official client's, on a classpath without the SDK.
 */
@OptIn(ExperimentalA2aClientApi::class)
class OfficialSdkPresenceTest {
    @Test
    fun theOfficialClientIsMadeWhereTheSdkIsAndSaysWhatIsMissingWhereItIsNot() {
        if (System.getProperty("agent.withoutOfficialSdk") == "true") {
            val refused = assertThrows(IllegalStateException::class.java) { OfficialA2aClientModule() }
            val message = refused.message.orEmpty()
            listOf(
                "org.a2aproject.sdk:a2a-java-sdk-client is missing",
                "org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final",
                "org.a2aproject.sdk:a2a-java-sdk-http-client-android:1.1.0.Final",
                "a2a-java-sdk-common",
                "DirectA2aClientModule",
            ).forEach { assertTrue(message, it in message) }
        } else {
            OfficialA2aClientModule().factory().create(A2aClientConfig()).close()
        }
    }
}
