package cc.noharry.agent.appserver.protocol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The registry ([AppServerMethods]) lists every request and notification this protocol defines, each once. */
class AppServerMethodsTest {
    @Test
    fun registryListsEveryMethodTheProtocolDefines() {
        val defined = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> METHOD.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()
        val registered = AppServerMethods.all.map { it.name }

        assertEquals("each method once in AppServerMethods", registered.size, registered.toSet().size)
        assertEquals("AppServerMethods lists every RequestMethod and NotificationMethod", defined.toList(), registered.sorted())
    }

    private companion object {
        val METHOD = Regex("""(?:Request|Notification)Method\(\s*"([^"]+)"""")
    }
}
