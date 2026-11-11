package cc.noharry.agent.appserver.protocol

/**
 * Marks the app server's entry points: its protocol client ([cc.noharry.agent.appserver.protocol.client.ServerClient]),
 * the server a host installs, its plugin interface and the first-party plugins. The app server and its protocol are
 * experimental as a whole and may change in a minor release; using an entry point without an opt-in is a warning.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The app server is experimental and may change in a minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalAppServerApi
