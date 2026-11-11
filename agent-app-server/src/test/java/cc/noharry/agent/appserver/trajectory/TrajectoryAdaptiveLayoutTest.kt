@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.view.TrajectoryInspectorTab
import cc.noharry.agent.appserver.protocol.view.inspectorTabsFor
import cc.noharry.agent.appserver.protocol.view.paginateRawText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inspector's tabs for records the projector makes (TrajectoryAdaptiveLayoutUiTest has the placement). */
class TrajectoryAdaptiveLayoutTest {

    @Test
    fun inspectorTabsAreDynamicByKindAndRawIsPaginatedCanonicalPayload() {
        val user = inspectorTabsFor(sessionEvent(type = cc.noharry.agent.spi.SessionEventType.USER_MESSAGE).toTrajectoryRecord())
        val tool = inspectorTabsFor(
            sessionEvent(
                type = cc.noharry.agent.spi.SessionEventType.TOOL_RESULT,
                safeMetadata = mapOf("callId" to "c1", "toolName" to "read_file"),
            ).toTrajectoryRecord(),
        )

        assertTrue(user.contains(TrajectoryInspectorTab.RAW))
        assertTrue(user.contains(TrajectoryInspectorTab.SUMMARY))
        assertFalse(user.contains(TrajectoryInspectorTab.RESULT))
        assertTrue(tool.contains(TrajectoryInspectorTab.RESULT))
        assertFalse(tool.contains(TrajectoryInspectorTab.REQUEST))
        assertEquals(listOf(""), paginateRawText(""))
    }
}
