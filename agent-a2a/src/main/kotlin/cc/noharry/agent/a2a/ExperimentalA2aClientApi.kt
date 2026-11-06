package cc.noharry.agent.a2a

/** Marks the outbound A2A client API, which is experimental and may change in a minor release. */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The Phase 0 A2A client facade is experimental and may change.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalA2aClientApi
