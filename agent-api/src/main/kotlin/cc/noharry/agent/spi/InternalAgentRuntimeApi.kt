package cc.noharry.agent.spi

/**
 * Marks declarations that are public only so the runtime's own modules and tests can reach them: test seams and
 * cache counters. They are not part of the API, may change or go in any release, and the API dumps leave them out.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to the Android agent runtime and may change or go in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class InternalAgentRuntimeApi
