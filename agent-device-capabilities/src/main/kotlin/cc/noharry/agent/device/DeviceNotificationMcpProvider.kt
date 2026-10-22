package cc.noharry.agent.device

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor

/** Optional Android notification MCP provider backed by NotificationManager. */
class DeviceNotificationMcpProvider internal constructor(
    private val gateway: DeviceNotificationGateway,
) : McpClientProvider, ToolCatalogCacheIdentity {
    override val serverId: String = NOTIFICATION_SERVER_ID
    override val displayName: String = "Device Notifications"
    override val catalogCacheIdentity: String = "$NOTIFICATION_SERVER_ID:v1"
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    constructor(context: Context) : this(AndroidDeviceNotificationGateway(context.applicationContext))

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(notificationListTool(), notificationCreateTool())

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        when (toolName) {
            NOTIFICATION_LIST -> failed(DEVICE_CAPABILITY_UNAVAILABLE, "Notification listing requires a host NotificationListenerService.")
            NOTIFICATION_CREATE -> createNotification(argumentsJson)
            else -> failed(DEVICE_CAPABILITY_UNAVAILABLE, "Unknown notification tool: $toolName")
        }

    private fun createNotification(argumentsJson: String): ToolCallResult {
        if (!gateway.canPostNotifications()) return failed(DEVICE_PERMISSION_DENIED, "Notification permission denied.")
        val json = parseObject(argumentsJson) ?: return failed(DEVICE_ARGUMENT_INVALID, "Expected JSON object arguments.")
        val title = json.stringField("title")?.takeIf { it.isNotBlank() } ?: return failed(DEVICE_ARGUMENT_INVALID, "Missing title.")
        val body = json.stringField("body").orEmpty()
        return runCatching {
            val id = gateway.createNotification(title, body)
            ToolCallResult(true, deviceSuccessJson(""""notificationId":$id"""))
        }.getOrElse { failed(DEVICE_CALL_FAILED, it.message ?: "Notification create failed.", retryable = true) }
    }
}

internal interface DeviceNotificationGateway {
    fun canPostNotifications(): Boolean
    fun createNotification(title: String, body: String): Int
}

private class AndroidDeviceNotificationGateway(private val context: Context) : DeviceNotificationGateway {
    override fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun createNotification(title: String, body: String): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        manager.notify(DEFAULT_NOTIFICATION_ID, notification)
        return DEFAULT_NOTIFICATION_ID
    }
}

private fun notificationListTool(): ToolDescriptor =
    ToolDescriptor(
        name = NOTIFICATION_LIST,
        title = "List notifications",
        description = "List notifications when the host supplies a NotificationListenerService.",
        inputSchemaJson = """{"type":"object","properties":{}}""",
    )

private fun notificationCreateTool(): ToolDescriptor =
    ToolDescriptor(
        name = NOTIFICATION_CREATE,
        title = "Create notification",
        description = "Post a device notification through NotificationManager.",
        inputSchemaJson = """{"type":"object","required":["title"],"properties":{"title":{"type":"string"},"body":{"type":"string"}}}""",
    )

private fun failed(code: String, message: String, retryable: Boolean = false): ToolCallResult =
    ToolCallResult(false, deviceErrorJson(code, message, retryable), retryable)

private const val NOTIFICATION_SERVER_ID = "device_notification"
private const val NOTIFICATION_LIST = "notification.list"
private const val NOTIFICATION_CREATE = "notification.create"
private const val CHANNEL_ID = "agent_runtime_device_capabilities"
private const val CHANNEL_NAME = "Agent Runtime Device Capabilities"
private const val DEFAULT_NOTIFICATION_ID = 7001
