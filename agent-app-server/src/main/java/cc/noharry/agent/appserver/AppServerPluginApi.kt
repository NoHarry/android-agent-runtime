package cc.noharry.agent.appserver

/**
 * What a plugin of the app server builds on beyond what a host uses: public for plugin modules, which opt in by
 * compiler option. It may change between versions, with the plugins that ship beside the server changing with it.
 */
@RequiresOptIn(
    message = "For app server plugins; it may change between versions.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class AppServerPluginApi
