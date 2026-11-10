package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import org.a2aproject.sdk.spec.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class OfficialTaskStatusTest {
    @Test
    fun rejectedAndFailedStayApart() {
        assertEquals(A2aTask.Status.REJECTED, officialTaskStatus(TaskState.TASK_STATE_REJECTED))
        assertEquals(A2aTask.Status.FAILED, officialTaskStatus(TaskState.TASK_STATE_FAILED))
        assertEquals(A2aTask.Status.INPUT_REQUIRED, officialTaskStatus(TaskState.TASK_STATE_AUTH_REQUIRED))
    }
}
