package cc.noharry.agent.kernel.core.tools

import kotlinx.coroutines.CancellationException

/**
 * Whether [this], thrown by a tool provider or an exec engine, is that call's failure: anything but cancellation and
 * running out of memory. An Error a provider throws (a native library that did not load, a class missing at run time)
 * fails its call as an exception does, so the model reads it and the run goes on.
 */
internal fun Throwable.isCallFailure(): Boolean = this !is CancellationException && this !is OutOfMemoryError

/**
 * What the model reads of [this] as a call's failure: an exception's message ([fallback] when it has none); for an
 * Error its kind as well (`UnsatisfiedLinkError: no quickjs in java.library.path`), which says more than its message.
 */
internal fun Throwable.callFailureMessage(fallback: String): String = when {
    this is Exception -> message ?: fallback
    message.isNullOrBlank() -> javaClass.simpleName
    else -> "${javaClass.simpleName}: $message"
}
