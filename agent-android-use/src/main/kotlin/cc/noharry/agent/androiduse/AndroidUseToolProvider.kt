package cc.noharry.agent.androiduse

import android.content.Context
import android.os.SystemClock
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Candidate Android Use tools over [AndroidUseAccessibilityService]: `android_observe` and
 * `android_action`. Not frozen.
 *
 * An action must name the latest observation of its display, then returns the next observation, so one
 * step costs one model round. Coordinates are screenshot pixels when a screenshot is attached.
 * With [AndroidUseOptions.preloadTools] both tools are offered from the first round instead of only
 * after `search_tools` finds them.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class AndroidUseToolProvider internal constructor(
    private val device: AndroidUseDevice,
    private val presenter: AndroidUsePresenter,
    private val options: AndroidUseOptions,
    private val lease: AndroidUseLease,
    private val nowMillis: () -> Long,
) : McpClientProvider, ToolPrefetchProvider {
    /** Builds the provider over this app's accessibility service; the host must have enabled it. */
    constructor(
        context: Context,
        presenter: AndroidUsePresenter = AndroidUsePresenter.None,
        options: AndroidUseOptions = AndroidUseOptions(),
    ) : this(AccessibilityAndroidUseDevice(context.applicationContext), presenter, options, AndroidUseLease.process, SystemClock::elapsedRealtime)

    override val serverId: String = ANDROID_USE_SERVER_ID
    override val displayName: String = "Android Use"

    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    private val book = ObservationBook(options.maxElements)
    private val runner = AndroidUseActionRunner(device, presenter, options.presenterTimeoutMillis)
    private val announcedRuns = mutableSetOf<String>()

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult =
        if (device.isConnected()) ToolInitializeResult.connected() else ToolInitializeResult.failed(NOT_CONNECTED_MESSAGE)

    override suspend fun listTools(): List<ToolDescriptor> = listOf(observeToolDescriptor(), actionToolDescriptor())

    override val remotePrefetchEnabled: Boolean get() = false

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (options.preloadTools) listOf(ANDROID_OBSERVE, ANDROID_ACTION) else emptyList()

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val args = parseArguments(argumentsJson) ?: return failureResult(ARGUMENT_INVALID, "Arguments must be a JSON object.")
        if (!device.isConnected()) return failureResult(SERVICE_NOT_CONNECTED, NOT_CONNECTED_MESSAGE)
        if (!lease.acquire(context.sessionId, context.runId, nowMillis())) {
            return failureResult(DEVICE_BUSY, "Another task is using the screen. Try again shortly.")
        }
        return try {
            announce(context.runId)
            when (toolName) {
                ANDROID_OBSERVE -> successResult(observe(args.int("displayId") ?: 0))
                ANDROID_ACTION -> act(args)
                else -> failureResult(ARGUMENT_INVALID, "Unknown Android Use tool: $toolName")
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failureResult(DEVICE_ERROR, error.message ?: error.javaClass.simpleName)
        } finally {
            lease.touch(context.runId, nowMillis())
        }
    }

    private suspend fun act(args: JsonObject): ToolCallResult {
        val displayId = args.int("displayId") ?: 0
        val action = AndroidUseAction.fromWire(args.string("action"))
            ?: return failureResult(ARGUMENT_INVALID, "Unknown action: ${args.string("action")}")
        val latest = book.latest(displayId)
        if (latest == null || args.string("observationId") != latest.id) {
            return failureResult(STALE_OBSERVATION, "Act on the latest observation; a fresh one is attached.", observe(displayId))
        }
        val outcome = try {
            runner.perform(action, args, latest)
        } catch (_: StaleNodeException) {
            return failureResult(STALE_OBSERVATION, STALE_NODES_MESSAGE, observe(displayId))
        }
        if (outcome is ActionOutcome.Refused) return failureResult(outcome.code, outcome.message)
        val done = outcome as ActionOutcome.Done
        device.awaitSettled(SETTLE_QUIET_MILLIS, SETTLE_MAX_MILLIS)
        var next = observe(displayId)
        var changed = screenChanged(latest, next, done.focus)
        if (!changed && action.mayRedrawLate()) {
            // A media card or toggle redraws after a service round trip; look once more before saying "no change".
            delay(LATE_CHANGE_MILLIS)
            next = observe(displayId)
            changed = screenChanged(latest, next, done.focus)
        }
        if (!done.ok) return failureResult(ACTION_FAILED, "The device did not perform ${action.wireName}.", next)
        return successResult(next) {
            put("action", action.wireName)
            done.target?.let { put("target", it) }
            put("changed", changed)
        }
    }

    /** Nodes or pixels changed, on the whole screen or right where the action landed (a play/pause icon). */
    private fun screenChanged(before: Observation, after: Observation, focus: PixelRect?): Boolean {
        val beforeSignature = before.capture?.signature
        val afterSignature = after.capture?.signature
        return before.state.fingerprint() != after.state.fingerprint() ||
            visuallyChanged(beforeSignature, afterSignature) == true ||
            focus?.let { visuallyChangedNear(beforeSignature, afterSignature, it, after.state.width, after.state.height) } == true
    }

    private suspend fun observe(displayId: Int): Observation {
        val (capture, note) = captureScreen(displayId)
        val state = device.readScreen(displayId)
        return book.record(state, capture, device.ownPackage(), note)
    }

    private suspend fun captureScreen(displayId: Int): Pair<ScreenCapture?, String> {
        if (!options.screenshots) return null to SCREENSHOT_TEXT_ONLY
        val capture = try {
            runner.bounded { beforeCapture() }
            device.capture(displayId, options.maxScreenshotPixels)
        } finally {
            runner.boundedOnExit { afterCapture() }
        }
        return capture to if (capture != null) SCREENSHOT_ATTACHED else SCREENSHOT_UNAVAILABLE
    }

    private suspend fun announce(runId: String) {
        if (runId in announcedRuns) return
        if (announcedRuns.size >= MAX_ANNOUNCED_RUNS) announcedRuns.clear()
        announcedRuns += runId
        runner.bounded(options.runStartTimeoutMillis) { onRunStarted(runId) }
    }
}

internal const val ARGUMENT_INVALID = "ARGUMENT_INVALID"
internal const val SERVICE_NOT_CONNECTED = "SERVICE_NOT_CONNECTED"
internal const val DEVICE_BUSY = "DEVICE_BUSY"
internal const val STALE_OBSERVATION = "STALE_OBSERVATION"
internal const val ELEMENT_NOT_FOUND = "ELEMENT_NOT_FOUND"
internal const val APP_NOT_FOUND = "APP_NOT_FOUND"
internal const val APP_AMBIGUOUS = "APP_AMBIGUOUS"
internal const val NOT_ADJUSTABLE = "NOT_ADJUSTABLE"
internal const val NOT_SCROLLABLE = "NOT_SCROLLABLE"
internal const val ACTION_FAILED = "ACTION_FAILED"
internal const val DEVICE_ERROR = "DEVICE_ERROR"
internal const val SYSTEM_AREA_BLOCKED = "SYSTEM_AREA_BLOCKED"
internal const val SCREENSHOT_ATTACHED = "attached"
internal const val SCREENSHOT_TEXT_ONLY = "disabled: model accepts text only"
internal const val SCREENSHOT_UNAVAILABLE = "unavailable: use the elements"
private const val NOT_CONNECTED_MESSAGE = "The Android Use accessibility service is not connected."
private const val STALE_NODES_MESSAGE = "The screen was read again since that observation; act on the fresh one attached."
private const val SETTLE_QUIET_MILLIS = 400L
private const val SETTLE_MAX_MILLIS = 2_000L
private const val LATE_CHANGE_MILLIS = 900L
private const val MAX_ANNOUNCED_RUNS = 32
