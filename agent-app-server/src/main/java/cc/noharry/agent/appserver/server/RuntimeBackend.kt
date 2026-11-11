@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.content.Context
import android.net.Uri
import cc.noharry.agent.appserver.mcp.McpToolCatalogs
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.ModelSelectParams
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderFetchParams
import cc.noharry.agent.appserver.protocol.ProviderModelsParams
import cc.noharry.agent.appserver.protocol.ProviderSaveParams
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SkillCheckView
import cc.noharry.agent.appserver.protocol.SubagentSettingsView
import cc.noharry.agent.appserver.protocol.SubagentToolKind
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.RequestIds
import cc.noharry.agent.appserver.runtime.RunIntake
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.runtime.safPickerUnavailableMessage
import cc.noharry.agent.appserver.runtime.saveBasicTool
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.customSkillTemplate
import cc.noharry.agent.appserver.skills.CustomSkills
import cc.noharry.agent.appserver.skills.checkCustomSkill
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.loadSubagentKinds
import cc.noharry.agent.appserver.skills.resetSubagentKindTools
import cc.noharry.agent.appserver.skills.runtimeSkillText
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.appserver.skills.saveSubagentKind
import cc.noharry.agent.appserver.skills.saveSubagentKindTools
import cc.noharry.agent.appserver.skills.saveSubagentSettings
import cc.noharry.agent.appserver.skills.subagentKindText
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** The backend over [RuntimeSession]: each request as the console's buttons do it. */
internal class RuntimeBackend(private val app: Context) : ServerBackend, SettingsBackend {
    /** What attaching a screen to the runtime refreshes, as it did when each screen attached by itself. */
    override fun clientJoined() = RuntimeSession.attach(app) {}.invoke()

    override fun startThread() = RuntimeSession.newSession(app)

    override fun readThread(threadId: String) = RuntimeSession.threads.read(app, threadId)

    override fun followThread(threadId: String) = RuntimeSession.threads.load(app, threadId)

    override fun unfollowThread(threadId: String) = RuntimeSession.threads.release(threadId)

    override fun selectThread(threadId: String) = RuntimeSession.selectSession(app, threadId)

    override fun deleteThread(threadId: String) = RuntimeSession.deleteSession(app, threadId)

    override fun startTurn(text: String) {
        RuntimeSession.submitText(app, text)
    }

    override fun startTurnIn(threadId: String, text: String) {
        background.launch(Dispatchers.Main.immediate) { RuntimeSession.submitExternal(app, text, RunIntake(RequestIds.next(), threadId)) }
    }

    override fun steer(text: String) {
        RuntimeSession.postSteeringText(text)
    }

    override fun interrupt(threadId: String?) {
        if (threadId == null) RuntimeSession.stopRun() else RuntimeSession.threads.stop(threadId)
    }

    override fun respondApproval(pendingId: String, decision: ApprovalDecision) {
        RuntimeSession.resolveConfirmation(pendingId, approve = decision == ApprovalDecision.APPROVE)
    }

    override fun selectModel(params: ModelSelectParams) {
        params.provider?.let { RuntimeSession.selectProvider(app, it) }
        params.profileId?.let { RuntimeSession.selectProfile(app, it) }
        params.manualProfileEnabled?.let { RuntimeSession.setManualProfileEnabled(app, it) }
        params.model?.let { RuntimeSession.selectModel(app, it) }
        params.variant?.let { RuntimeSession.selectVariant(app, it) }
    }

    override fun setRun(params: RunSettingsParams) {
        params.confirmationMode?.let(RuntimeSession::selectConfirmationMode)
        params.simulatedBackend?.let(RuntimeSession::selectBackend)
        if (params.toggleStream) RuntimeSession.toggleStream()
    }

    override fun reloadConfig() = RuntimeSession.reloadConfig(app)

    override fun saveProvider(params: ProviderSaveParams) = with(params) {
        RuntimeSession.saveProviderOverride(app, provider, apiKey, model, baseUrl, endpoint)
    }

    override fun createProvider(params: ProviderCreateParams): String = with(params) {
        RuntimeSession.saveNewProviderProfile(app, label, provider, endpoint, apiKey, model, baseUrl)
    }

    override fun deleteProvider(profileId: String) = RuntimeSession.deleteProviderProfile(app, profileId)

    override suspend fun fetchModels(params: ProviderFetchParams): List<String> = suspendCancellableCoroutine { waiting ->
        with(params) {
            RuntimeSession.fetchProviderModels(app, profileId, provider, endpoint, apiKey, model, baseUrl) { result ->
                result.fold(
                    onSuccess = { waiting.resume(it) },
                    onFailure = { waiting.resumeWithException(IOException(it.message ?: it.javaClass.simpleName, it)) },
                )
            }
        }
    }

    override fun saveProviderModels(params: ProviderModelsParams) =
        RuntimeSession.saveProviderModels(app, params.editor, params.models)

    override fun setMcpEnabled(id: String, enabled: Boolean) = RuntimeSession.setMcpProviderEnabled(app, id, enabled)

    // RuntimeSession words these refusals itself; they come through as they are until it reports them as findings.
    override fun saveMcp(draft: McpServerDraft): Finding? = RuntimeSession.saveMcpServer(app, draft)?.let(::text)

    override fun deleteMcp(id: String) = RuntimeSession.deleteMcpServer(app, id)

    override fun selectWorkspace(uri: String) = RuntimeSession.selectSafWorkspace(app, Uri.parse(uri))

    override fun clearWorkspace() = RuntimeSession.clearSafWorkspace(app)

    override fun reportWorkspaceUnavailable(message: String) =
        RuntimeSession.reportSafWorkspaceUnavailable(safPickerUnavailableMessage(message))

    override suspend fun clearData(also: Set<String>): Boolean = suspendCancellableCoroutine { waiting ->
        RuntimeSession.clearRuntimeData(app, also) { cleared -> waiting.resume(cleared) }
    }

    override fun refreshTrajectory() = RuntimeSession.refreshTrajectory(app)

    override fun loadOlderTrajectory() = RuntimeSession.loadOlderTrajectoryRuns(app)

    override fun loadTrajectoryRun(runId: String) = RuntimeSession.loadTrajectoryRun(app, runId)

    override fun trajectoryRaw(sessionId: String, eventId: String): String? = RuntimeSession.trajectoryRaw(app, sessionId, eventId)

    override fun answerQuestion(question: UserQuestion, text: String) {
        RuntimeSession.answerQuestion(app, question, text)
    }

    override fun toolsChanged(serverIds: List<String>) = serverIds.forEach { McpToolCatalogs.onServerChanged(app, it) }

    /**
     * The app came to the front or left it: the next run knows ([AppScreen], [cc.noharry.agent.appserver.runtime.RunFacts.appInFront]),
     * and each plugin hears it (a voice may start its microphone service then).
     */
    override fun appInFront(inFront: Boolean) {
        AppScreen.inFront = inFront
        AppServer.pluginHost?.appInFront(inFront)
    }

    override fun setSessionPolicy(policy: SessionPolicy) = SessionSettings.save(app, policy)

    override fun setBasicTool(name: String, enabled: Boolean) = saved { saveBasicTool(app, name, enabled) }


    override fun setSkill(id: String, enabled: Boolean) = saved { saveRuntimeSkill(app, id, enabled) }

    /** A skill's text; no id is a new skill, which starts from the template. */
    override fun skillText(id: String): String = if (id.isBlank()) {
        customSkillTemplate()
    } else {
        customSkillText(app, id) ?: loadRuntimeSkills(app).firstOrNull { it.id == id }?.let { runtimeSkillText(app, it) }.orEmpty()
    }

    override fun checkSkill(text: String, editing: String?): SkillCheckView =
        checkCustomSkill(text, CustomSkills.takenNames(app, editing)).view()

    override fun saveSkill(text: String, editing: String?): Finding? = CustomSkills.save(app, text, editing).also { PreferencesSource.changed() }

    override fun deleteSkill(id: String) = saved { CustomSkills.delete(app, id) }

    override fun setSubagents(settings: SubagentSettingsView) = saved { saveSubagentSettings(app, settings.settings()) }

    override fun setSubagentKind(id: String, enabled: Boolean) = saved { saveSubagentKind(app, id, enabled) }

    override fun setSubagentTools(id: String, tools: Set<SubagentToolKind>, maxLookups: Int) =
        saved { saveSubagentKindTools(app, id, tools.mapTo(LinkedHashSet()) { it.group() }, maxLookups) }

    override fun resetSubagentTools(id: String) = saved { resetSubagentKindTools(app, id) }

    override fun subagentKindText(id: String): String =
        loadSubagentKinds(app).firstOrNull { it.id == id }?.let { subagentKindText(app, it) }.orEmpty()

    /** Saves a setting, then has front ends shown what it is now. */
    private inline fun saved(block: () -> Unit) {
        block()
        PreferencesSource.changed()
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
