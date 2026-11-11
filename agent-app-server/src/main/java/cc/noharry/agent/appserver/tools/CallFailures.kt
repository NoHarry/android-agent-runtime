package cc.noharry.agent.appserver.tools

import kotlinx.coroutines.CancellationException

/**
 * Whether [this], thrown by a tool provider, a script engine or a run, ends only what threw it: anything but
 * cancellation and running out of memory. An Error (a native library that did not load, a class missing at run time)
 * counts as an exception does.
 */
internal fun Throwable.isCallFailure(): Boolean = this !is CancellationException && this !is OutOfMemoryError

/** [this] as a person or the model reads it: an exception's message, else its kind; an Error's kind and message. */
internal fun Throwable.failureText(): String = when {
    message.isNullOrBlank() -> javaClass.simpleName
    this is Exception -> message.orEmpty()
    else -> "${javaClass.simpleName}: $message"
}
