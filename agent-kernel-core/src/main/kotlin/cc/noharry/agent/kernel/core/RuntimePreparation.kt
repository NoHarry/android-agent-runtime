package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelMessage

/** Pre-model preparation on the existing catalog/activation path, measured with a monotonic clock. */
internal suspend fun prepareRuntimeTools(
    input: String, context: MaterializerContext, registry: ToolProviderRegistry,
    activations: ToolActivationStore, recorder: LoopSessionRecorder,
) {
    val started = System.nanoTime()
    val catalog = registry.allTools(context)
    activations.dropInheritedOutside(context.runId, catalog.mapTo(hashSetOf()) { it.qualifiedName })
    val prefetchStarted = System.nanoTime()
    prefetchRuntimeTools(input, context, registry, activations, recorder)
    recorder.recordPreparationTimings(context, registry.preparationTimings(context) + mapOf(
        "prefetchMs" to preparationElapsedMs(prefetchStarted),
        "toolPreparationMs" to preparationElapsedMs(started),
    ))
}

/**
 * The host's run context (conversation recap, the previous exchange, instructions picked for the request) as the message
 * right after the input. What changes from run to run stays out of the system prompt, so the provider's prompt cache
 * keeps the system prompt and the tool list after it.
 */
internal fun runContextMessage(runContext: String, asSystem: Boolean = false): ModelMessage? =
    runContext.takeIf { it.isNotBlank() }?.let {
        // A provider that takes system updates in history (DeepSeek) gives the context system weight at no cache cost.
        ModelMessage(role = if (asSystem) "system" else "user", content = "$RUN_CONTEXT_HEADER\n$it")
    }

internal fun preparationElapsedMs(started: Long): Long =
    ((System.nanoTime() - started) / NANOS_PER_MILLI).coerceAtLeast(0L)

private const val NANOS_PER_MILLI = 1_000_000L
