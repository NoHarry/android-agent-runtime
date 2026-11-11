package cc.noharry.agent.appserver

/**
 * Public only for another module's tests, never its main code: the one entry point they put the server into a state
 * through ([cc.noharry.agent.appserver.server.AppServerTesting]), and the plugin kernel a plugin module's tests run their
 * plugin on ([cc.noharry.agent.appserver.server.PluginHost] and what it adds). Not for hosts: it may change or go without
 * notice. Another module's tests opt in by compiler option.
 */
@RequiresOptIn(
    message = "Inside the app server, public only for tests; not for hosts.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalAppServerApi
