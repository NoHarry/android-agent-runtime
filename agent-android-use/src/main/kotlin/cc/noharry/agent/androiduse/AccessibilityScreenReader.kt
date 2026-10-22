package cc.noharry.agent.androiduse

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Flattens one display's visible windows into [ScreenNode]s, walking every window the way 可见即可说
 * does, and keeps the live nodes of each display's latest read so actions can reach them by handle.
 */
internal class AccessibilityScreenReader(
    private val ownPackage: String,
) {
    private val live = LiveNodeTables<AccessibilityNodeInfo>()

    /** The live node behind [handle]; null once its display has been read again. */
    fun node(handle: Int): AccessibilityNodeInfo? = live.resolve(handle)

    fun read(service: AccessibilityService, displayId: Int): ScreenState {
        val windows = windowsOf(service, displayId).filter { it.root?.packageName?.toString() != ownPackage }
        val nodes = mutableListOf<ScreenNode>()
        val liveNodes = HashMap<Int, AccessibilityNodeInfo>()
        windows.forEach { window -> window.root?.let { root -> walk(root, nodes, liveNodes) } }
        live.publish(displayId, liveNodes)
        val size = displaySize(service, displayId)
        return ScreenState(displayId, size.x, size.y, foregroundPackage(windows), nodes, systemAreas(windows, size))
    }

    /** Status bar and dock windows; the device puts climate and seat controls there. */
    private fun systemAreas(windows: List<AccessibilityWindowInfo>, size: Point): List<PixelRect> =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }.map { window ->
            val rect = Rect().also(window::getBoundsInScreen)
            PixelRect(rect.left, rect.top, rect.right, rect.bottom)
        }.filter { !it.isEmpty() && isSystemBar(it, size.x, size.y) }

    private fun walk(root: AccessibilityNodeInfo, out: MutableList<ScreenNode>, liveNodes: MutableMap<Int, AccessibilityNodeInfo>) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty() && out.size < MAX_NODES) {
            val (node, ancestorClickable) = stack.removeLast()
            if (!node.isVisibleToUser) continue
            val handle = live.nextHandle()
            liveNodes[handle] = node
            out += node.toScreenNode(handle, ancestorClickable)
            val childContext = ancestorClickable || node.isClickable
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { stack.addLast(it to childContext) }
            }
        }
    }

    private fun AccessibilityNodeInfo.toScreenNode(handle: Int, ancestorClickable: Boolean): ScreenNode {
        val rect = Rect().also(::getBoundsInScreen)
        return ScreenNode(
            handle = handle,
            text = text?.toString()?.trim().orEmpty(),
            description = contentDescription?.toString()?.trim().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            packageName = packageName?.toString().orEmpty(),
            bounds = PixelRect(rect.left, rect.top, rect.right, rect.bottom),
            clickable = isClickable,
            inClickableContext = ancestorClickable,
            editable = isEditable,
            scrollable = isScrollable,
            className = className?.toString()?.substringAfterLast('.').orEmpty(),
            range = rangeInfo?.let { NodeRange(it.min, it.max, it.current) },
        )
    }

    private fun windowsOf(service: AccessibilityService, displayId: Int): List<AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.windowsOnAllDisplays.get(displayId).orEmpty()
        } else if (displayId == DEFAULT_DISPLAY_ID) {
            service.windows.orEmpty()
        } else {
            emptyList()
        }

    private fun foregroundPackage(windows: List<AccessibilityWindowInfo>): String =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName?.toString()
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun displaySize(service: AccessibilityService, displayId: Int): Point {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        return Point().also { size -> display?.getRealSize(size) }
    }
}

internal const val DEFAULT_DISPLAY_ID = 0
private const val MAX_NODES = 1_500
