package cc.noharry.agent.appserver.server

/**
 * Whether the app's own screens are in front, as the app last said (`app/inFront`): what the person looks at then is a
 * front end's conversation. Each run reads it as [cc.noharry.agent.appserver.runtime.RunFacts.appInFront] (a plugin that
 * draws pages draws only then).
 */
internal object AppScreen {
    @Volatile var inFront: Boolean = false
        set(value) {
            field = value
            reports = true
        }

    /** Whether the app says when its screens come and go at all: a host that never does has no conversation on screen. */
    @Volatile var reports: Boolean = false
        private set
}
