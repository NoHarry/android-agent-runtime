package cc.noharry.agent.kernel.core.context
/** Reattach captured data-only context without invoking providers or turn observers twice. */
internal fun BootstrapPromptResolver.withContextSuffix(suffix: String): BootstrapPromptResolver =
    if (suffix.isBlank()) this else BootstrapPromptResolver { scene, session ->
        val base = resolve(scene, session)
        base.copy(text = base.text + "\n\n" + suffix)
    }
