package cc.noharry.agent.appserver.sample.notes

// guide:start first-agent
import android.app.Application
import cc.noharry.agent.appserver.model.ModelPreset
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig

/** Starts the app server as a notes assistant when the process starts, before any screen or service. */
class NotesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(
            this,
            AppServerConfig(
                name = "agent-app-sample-notes",
                persona = Persona(
                    name = "Notes",
                    identity = "You are Notes, an assistant that keeps the user's notes on this device.",
                ),
                tools = { context -> listOf(NotesTools(context)) },
                rules = listOf(
                    "Save what the user asks you to remember with the notes tools, one note per thing to remember.",
                    "Read the notes before answering about them, and never make a note up.",
                ),
                // Saving a note acts but needs no asking; clearing them all (it acts too) asks, as a tool that acts does.
                unaskedTools = setOf("mcp__notes__add_note"),
                // A note that looks like a password asks all the same.
                hooks = listOf(AskBeforeSavingSecrets),
                // Counting words, which the person can turn off.
                plugins = listOf(NoteStatsPlugin),
                model = builtModel(),
                // The screen answers what asks; a request from the service alone waits for it, or is not done.
                approvals = ConfirmationMode.ASK_EVERY_TIME,
            ),
        )
    }
}

/** The model service this build carries (-PnotesModelBaseUrl, -PnotesModel, -PnotesModelKey); none when left empty. */
private fun builtModel(): ModelPreset? =
    if (BuildConfig.MODEL_BASE_URL.isBlank() || BuildConfig.MODEL_NAME.isBlank()) {
        null
    } else {
        ModelPreset(baseUrl = BuildConfig.MODEL_BASE_URL, model = BuildConfig.MODEL_NAME, apiKey = BuildConfig.MODEL_KEY)
    }
// guide:end first-agent
