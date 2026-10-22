package cc.noharry.agent.device

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNotificationMcpProviderTest {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresSerializedConcurrency() {
        assertEquals(
            ProviderConcurrencyDeclaration.serialized(),
            DeviceNotificationMcpProvider(FakeNotificationGateway()).providerConcurrency,
        )
    }

    @Test
    fun exposesNotificationTools() = runBlocking {
        val tools = DeviceNotificationMcpProvider(FakeNotificationGateway()).listTools().map { it.name }

        assertEquals(listOf("notification.list", "notification.create"), tools)
    }

    @Test
    fun createNotificationUsesGateway() = runBlocking {
        val result = DeviceNotificationMcpProvider(FakeNotificationGateway()).callTool(
            "notification.create",
            """{"title":"Build done","body":"Validation passed"}""",
            callContext(),
        )

        assertTrue(result.success)
        assertTrue(result.resultJson.contains("notificationId"))
    }

    @Test
    fun deniedPermissionReturnsStructuredError() = runBlocking {
        val result = DeviceNotificationMcpProvider(FakeNotificationGateway(canPost = false)).callTool(
            "notification.create",
            """{"title":"Build done"}""",
            callContext(),
        )

        assertFalse(result.success)
        assertTrue(result.resultJson.contains(DEVICE_PERMISSION_DENIED))
    }

    @Test
    fun listReportsUnavailableWithoutHostListener() = runBlocking {
        val result = DeviceNotificationMcpProvider(FakeNotificationGateway()).callTool(
            "notification.list",
            "{}",
            callContext(),
        )

        assertFalse(result.success)
        assertTrue(result.resultJson.contains(DEVICE_CAPABILITY_UNAVAILABLE))
    }
}

private class FakeNotificationGateway(
    private val canPost: Boolean = true,
) : DeviceNotificationGateway {
    override fun canPostNotifications(): Boolean = canPost
    override fun createNotification(title: String, body: String): Int = 7
}

private fun callContext(): ToolCallContext =
    ToolCallContext(sessionId = "session-1", runId = "run-1", requestId = "request-1")
