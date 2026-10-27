package cc.noharry.agent.kernel.core.tools
/** Copies and validates host tool exclusions. Candidate configuration; finish is protocol-required. */
fun snapshotDisabledNativeTools(names: Set<String>): Set<String> {
    require("finish" !in names) { "finish is required by the runtime protocol." }
    val known = NATIVE_TOOLS.mapTo(mutableSetOf()) { it.name }
    require(names.all { it in known }) { "Unknown native tool exclusion." }
    return names.toSet()
}
