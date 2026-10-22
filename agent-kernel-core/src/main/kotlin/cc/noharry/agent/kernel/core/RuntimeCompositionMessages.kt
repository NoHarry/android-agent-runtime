package cc.noharry.agent.kernel.core

object RuntimeCompositionMessages {
    const val DEFAULT_PROFILE_ID: String = "default"
    const val MINIMAL_PROFILE_ID: String = "minimal"
    const val DIRECT_PLUGIN_ID: String = "runtime-direct-capabilities"
    const val UNSUPPORTED_FACTORY_COMPOSITION: String =
        "unsupported SESSION_FACTORY composition"
    const val PRODUCED_SESSION_FACTORY_MODE: String =
        "produced instance declared SESSION_FACTORY"
    const val PRODUCED_CONCURRENCY_EXCEEDS_BINDING: String =
        "produced maxConcurrentCalls exceeds SESSION_FACTORY binding cap"
    const val MIXED_CAPABILITY_MODE: String =
        "mixed shared and SESSION_FACTORY bindings"
    const val AMBIGUOUS_DIAGNOSTIC_ID: String = "ambiguous capability diagnostic"
    const val NONCANONICAL_BUILTIN_KEY: String =
        "noncanonical built-in capability key"
    const val SESSION_CAPABILITIES_DRAINED: String = "session capabilities drained"
    const val DUPLICATE_CONTEXT_PROVIDER: String = "Duplicate contextProvider id"
    const val DUPLICATE_TURN_OBSERVER: String = "Duplicate turnObserver id"
    const val BLANK_CONTEXT_PROVIDER_ID: String = "contextProvider id must not be blank."
    const val BLANK_TURN_OBSERVER_ID: String = "turnObserver id must not be blank."
    const val MISSING_MODEL: String = "model is required"
    const val MISSING_WORKSPACE: String = "workspace is required"
    const val BUILDER_ALREADY_USED: String = "KernelRuntimeBuilder.build is one-shot"
    const val UNSUPPORTED_INSTALLED_CAPABILITY: String = "unsupported installed capability"
}
