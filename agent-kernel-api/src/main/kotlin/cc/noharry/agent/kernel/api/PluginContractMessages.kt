package cc.noharry.agent.kernel.api

/** Shared diagnostic fragments for experimental plugin-contract failures. */
@ExperimentalAgentRuntimeApi
object PluginContractMessages {
    const val BLANK_PLUGIN_ID: String = "Plugin id must not be blank."
    const val BLANK_PROFILE_ID: String = "Profile id must not be blank."
    const val BLANK_SESSION_ID: String = "Session id must not be blank."
    const val BLANK_CAPABILITY_NAME: String = "Capability key name must not be blank."
    const val INVALID_CAPABILITY_NAME: String = "invalid capability diagnostic name"
    const val INVALID_PLUGIN_VERSION: String = "invalid plugin version"
    const val INVALID_KERNEL_VERSION: String = "invalid kernel version"
    const val INVALID_KERNEL_RANGE: String = "invalid kernel API range"
    const val INCOMPATIBLE_KERNEL_API: String = "incompatible kernel API"
    const val PROFILE_KERNEL_MISMATCH: String = "profile kernel API does not match compiler"
    const val PLUGIN_ID_MISMATCH: String = "plugin id does not match descriptor"
    const val DUPLICATE_PLUGIN_ID: String = "duplicate plugin id"
    const val MISSING_REQUIRED_CAPABILITY: String = "missing required capability"
    const val CAPABILITY_MAJOR_MISMATCH: String = "capability major mismatch"
    const val DUPLICATE_SINGLE: String = "duplicate SINGLE"
    const val DEPENDENCY_CYCLE: String = "dependency cycle"
    const val REQUIREMENT_OVERLAP: String = "required and optional requirements overlap"
    const val DIRECT_SESSION_FACTORY: String =
        "Direct SESSION_FACTORY instances fail because factory registration is required."
    const val FACTORY_REQUIRES_SESSION_MODE: String = "factory registration requires SESSION_FACTORY"
    const val FACTORY_SCOPE_UNSUPPORTED: String = "SESSION_FACTORY registration is not supported by this scope."
    const val PROVIDES_MISMATCH: String = "provided keys must exactly match declared provides"
    const val DEFAULT_NAMESPACE: String = "agent"
}
