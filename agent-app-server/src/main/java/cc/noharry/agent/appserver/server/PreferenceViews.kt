package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.hasWebLookup
import cc.noharry.agent.appserver.protocol.BasicToolView
import cc.noharry.agent.appserver.protocol.Preferences
import cc.noharry.agent.appserver.protocol.RuntimeSkillView
import cc.noharry.agent.appserver.protocol.SkillCheckView
import cc.noharry.agent.appserver.protocol.SubagentKindView
import cc.noharry.agent.appserver.protocol.SubagentSettingsView
import cc.noharry.agent.appserver.protocol.SubagentToolKind
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.loadBasicTools
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.skills.CustomSkills
import cc.noharry.agent.appserver.skills.SkillCheck
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.skills.SubagentSettings
import cc.noharry.agent.appserver.skills.SubagentToolGroup
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.loadSubagentKinds
import cc.noharry.agent.appserver.skills.subagentKindDefaults
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the person set, read from where each setting is kept, as front ends show it ([Preferences]). Built again when
 * something changed it ([changed]): a request that saved one, or the app's language (its words are in the device's).
 * Operating the screen's part ([Preferences.androidUse]) is its plugin's to publish, and stays as it published it.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object PreferencesSource {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = mutableChanges.asSharedFlow()

    fun changed() {
        mutableChanges.tryEmit(Unit)
    }

    fun read(context: Context): Preferences = Preferences(
        sessionPolicy = SessionSettings.policy.value,
        basicTools = loadBasicTools(context).map { BasicToolView(it.name, context.getString(it.description), it.enabled, it.required) },
        runtimeSkills = loadRuntimeSkills(context).map { RuntimeSkillView(it.id, it.label, it.description, it.enabled, custom = it.file != null) },
        // Without subagents ([BuiltInFeatures.subagents]) their settings are not there: off, and no types.
        subagents = if (AppHost.builtIns.subagents) AppSubagents.settings.value.view() else SubagentSettingsView(enabled = false),
        subagentKinds = if (AppHost.builtIns.subagents) loadSubagentKinds(context).map { it.view(context) } else emptyList(),
        webLookup = ProviderConfig.load(context).hasWebLookup(context),
    )
}


internal fun SubagentSettings.view() = SubagentSettingsView(allowDeviceControl, model, enabled, searchApi)

internal fun SubagentSettingsView.settings() = SubagentSettings(allowDeviceControl, model, enabled, searchApi)

internal fun SubagentToolGroup.kind(): SubagentToolKind = SubagentToolKind.valueOf(name)

internal fun SubagentToolKind.group(): SubagentToolGroup = SubagentToolGroup.valueOf(name)

private fun SubagentKind.view(context: Context): SubagentKindView {
    val shipped = subagentKindDefaults(id)
    return SubagentKindView(
        id = id,
        label = context.getString(label),
        description = description,
        maxLookups = maxLookups,
        searchApi = searchApi,
        enabled = enabled,
        tools = tools.mapTo(LinkedHashSet()) { it.kind() },
        requiresWebLookup = requiresWebLookup,
        defaultTools = shipped?.tools.orEmpty().mapTo(LinkedHashSet()) { it.kind() },
        defaultLookups = shipped?.maxLookups ?: maxLookups,
    )
}

/** A skill check with what it found, as the form shows it. */
internal fun SkillCheck.view() = SkillCheckView(
    name = name,
    description = description,
    errors = errors.map { it.finding() },
    warnings = warnings.map { it.finding() },
    normalized = normalized,
)

/** A custom skill's text, by its name. */
internal fun customSkillText(context: Context, name: String): String? = CustomSkills.text(context, name)
