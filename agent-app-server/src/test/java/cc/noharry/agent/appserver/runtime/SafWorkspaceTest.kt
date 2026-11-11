package cc.noharry.agent.appserver.runtime
import org.junit.Assert.assertEquals
import org.junit.Test

class SafWorkspaceTest {
    @Test
    fun safPickerUnavailableMessageNamesMissingDocumentTreePicker() {
        assertEquals(
            "SAF picker unavailable: no document-tree picker installed.",
            safPickerUnavailableMessage(),
        )
    }

    @Test
    fun safPickerUnavailableMessageIncludesLaunchFailureDetail() {
        val message = safPickerUnavailableMessage("launcher is not ready")

        assertEquals("SAF picker unavailable: launcher is not ready", message)
    }
}
