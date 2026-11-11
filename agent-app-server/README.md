# Agent app server: a guide for hosts

> **Experimental.** The app server and its protocol are modules of Android Agent Runtime, published as
> `cc.noharry:agent-app-server` and `cc.noharry:agent-app-protocol` and marked experimental: what this guide describes
> can change in a minor release, and their entry points (`ServerClient`, `AppServer`, `AppServerConfig`,
> `AppServerPlugin`) ask for an opt-in, `@ExperimentalAppServerApi`. Plugins are yours to write (see [Plugins](#plugins));
> none is published.

## What it is

The app server makes an Android app an agent: one agent runtime (a model, your tools, conversations kept on the device)
behind a JSON-RPC protocol of threads, turns and items, in the style of Codex's app-server. Your app is its **host**: it
installs the server when its process starts and says, as plain values, who the assistant is, which tools it has and
which model answers. Every front end (a screen, a background service, a broadcast receiver, a test) is a **client**: it
connects in-process with `ServerClient`, starts turns and follows the state the server publishes. Everything else
(writing files, the web, scripts, subagents, plugins, debug sockets, diagnostic files) is off until the host
turns it on.

The code below is quoted from four apps in this repository's `samples/`, which build with every check:
`agent-app-sample` (the smallest host), `agent-app-sample-notes` (a notes assistant with a screen and a background service),
`agent-app-sample-timer` (a kitchen timer with no screen and no model service, tested offline) and
`agent-app-sample-habits` (a habit tracker with no screen that answers approvals in code and switches a plugin of its
own off and on in its test). A test keeps the quotes in step with them.

## Add it to your app

Depend on `cc.noharry:agent-app-server` and `cc.noharry:agent-app-protocol`, on Maven Central from 0.5.0 and in
`agent-bom` with the runtime's other modules. Before a version is released, build it from this repository's source:
check the repository out beside your project, add `includeBuild("../android-agent-runtime")` to your
`settings.gradle.kts`, and depend on the same coordinates at `VERSION_NAME` of this repository's `gradle.properties`;
Gradle builds them, and the runtime modules they need, from the source. The samples, which build in this repository,
name the same modules as projects.

A whole module build file, the habits app's:

<!-- quote: samples/agent-app-sample-habits/build.gradle.kts build-file -->
```kotlin
plugins {
    // AGP 9 compiles Kotlin itself: no Kotlin plugin here. Its version is in the root build file.
    id("com.android.application")
}

/*
 * Tally, a habit tracker on the app server with no screen and no model service: a foreground service runs one turn
 * per intent on a scripted model, answers in code the approval a reset asks for, and can turn the habit list plugin
 * off and on. Its unit test runs the same turns offline, through the protocol.
 */
android {
    namespace = "cc.noharry.agent.appserver.sample.habits"
    compileSdk = 34
    defaultConfig {
        applicationId = "cc.noharry.agent.appserver.sample.habits"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        // The app server's workspace and event log use java.nio.file, which Android has from API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The server reads its own assets (the workspace it gives the model) in the test too.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
    // The app server is experimental: its entry points ask for this opt-in.
    compilerOptions.optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":agent-app-server"))
    implementation(project(":agent-app-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
```

- The plugins come from the root build file, whose `plugins { }` block declares them with their versions. AGP 9
  compiles Kotlin itself (built-in Kotlin), so an app module applies no Kotlin plugin; the Kotlin plugin on the root
  classpath sets the compiler version:

<!-- quote: build.gradle.kts root-plugins -->
```kotlin
id("com.android.application") version "9.4.1" apply false
id("com.android.library") version "9.4.1" apply false
id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
```

- The server needs `minSdk` 24 and Java 17; the samples compile against API 34.
- `ExperimentalAppServerApi`: the app server is experimental, and its entry points ask for an opt-in. The compiler
  option opts the whole module in; without it, each use is a warning.
- Core library desugaring with `desugar_jdk_libs_nio` 2.1.5 or later: the server's workspace and event log use
  `java.nio.file`, which Android has from API 26.
- `project(":agent-app-server")` and `project(":agent-app-protocol")` are the modules above (`cc.noharry:agent-app-server`
  and `cc.noharry:agent-app-protocol` outside this repository); `agent-app-server` gives
  you the runtime's `ToolProvider`, `RuntimeHook` and `ModelClient` as well, and `agent-app-protocol` is the protocol
  and its client, plain Kotlin. Clients run on coroutines (`kotlinx-coroutines-android`).
- For tests run offline under Robolectric ([Testing offline](#testing-offline-with-a-scripted-model)):
  `junit`, `robolectric` and `androidx.test:core`, and `unitTests.isIncludeAndroidResources` because the server reads
  its own assets.

Your manifest needs the Internet permission and your `Application` class. The server adds one receiver of its own,
`BootReceiver`, off unless a running plugin asks for the app to start after a reboot (`PluginContext.startOnBoot`); a
plugin's module brings what it needs in a manifest of its own (an alarm's receiver, a service, a permission). The notes app's manifest, with
its foreground service (see [A service with no screen](#a-service-with-no-screen)):

<!-- quote: samples/agent-app-sample-notes/src/main/AndroidManifest.xml manifest -->
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application
    android:name=".NotesApplication"
    android:allowBackup="false"
    android:label="@string/app_name"
    android:theme="@style/NotesTheme">
    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:windowSoftInputMode="adjustResize">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    <!-- Takes a request with no screen. Only a caller holding DUMP (adb's shell) may start it. -->
    <service
        android:name=".AgentService"
        android:exported="true"
        android:foregroundServiceType="dataSync"
        android:permission="android.permission.DUMP" />
</application>
```

## Where things live

Every name this guide uses that is not a sample's own, by package. `agent-app-server`'s public API is listed in
`agent-app-server/api/agent-app-server-levels.txt`, each line with its level: `host` (what this guide names, and the
interfaces a host may implement), `plugin` (`@AppServerPluginApi`, for plugin modules, which opt in) or `test`
(`@InternalAppServerApi`, for the server's own tests in other modules; never for an app's code). Every module's binary
API is in its `api/<module>.api`, as for the rest of the runtime. The protocol's methods are listed in
`AppServerMethods`, and each constant (`ClientRequests.TURN_START`, say) is a `RequestMethod` that names its wire method
and its parameter and result types (`Methods.kt` and one file per area, in `cc.noharry.agent.appserver.protocol`).

| Package | Module | Names |
|---|---|---|
| `cc.noharry.agent.appserver.server` | `agent-app-server` | `AppServer`, `AppServerConfig`, `AppServerPlugin`, `PluginContext`, `PluginSkill`, `PluginMcpServer`, `PluginToolServer`, `PublishedState`, `RouteModule` |
| `cc.noharry.agent.appserver.runtime` | `agent-app-server` | `Persona`, `BuiltInFeatures`, `RunAddOns`, `RunFacts`, `HostModels`, `VoicePort`, `ProviderPresets`, `ReplayCheck` |
| `cc.noharry.agent.appserver.model` | `agent-app-server` | `ModelPreset`, `ScriptedModel` |
| `cc.noharry.agent.appserver.skills` | `agent-app-server` | `RuntimeSkill` |
| `cc.noharry.agent.appserver.protocol` | `agent-app-protocol` | `ExperimentalAppServerApi`, `ClientInfo`, `ClientCapabilities`, `ClientRequests`, `SettingsRequests`, `PluginRequests`, `ServerNotifications`, `TurnStartParams`, `TextParams`, `TurnInterruptParams`, `ThreadIdParams`, `Empty`, `ApprovalRequest`, `ApprovalDecision`, `ApprovalRespondParams`, `ConfirmationMode`, `RunSettingsParams`, `ProviderCreateParams`, `ModelSelectParams`, `ModelProvider`, `ProviderEndpoint`, `PluginSetParams`, `ConversationItem` (with `TurnHeader`, `MessageItem`, `ToolCallItem`, `ConfirmationItem`, `RuntimePhaseItem`), `TranscriptRole`, `ToolCallStatus` |
| `cc.noharry.agent.appserver.protocol.client` | `agent-app-protocol` | `ServerClient`, `ServerState` |
| `cc.noharry.agent.appserver.protocol.jsonrpc` | `agent-app-protocol` | `JsonRpcNotification` |
| `cc.noharry.agent.spi` | the runtime's `agent-api`, which `agent-app-server` brings | `ToolProvider`, `ToolDescriptor`, `ToolCallResult`, `ToolCallContext`, `RuntimeHook`, `HookDecision`, `HookContext`, `ToolUse` |
| `cc.noharry.agent.spi.model` | the runtime's `agent-api` | `ModelClient`, `ModelRequest`, `ModelEvent`, `ModelMessage` |

Names not in the table are Android's, Kotlin's and `kotlinx.coroutines`' (`MainScope`, `CoroutineStart`, `async`,
`first`, `withTimeoutOrNull`, `Mutex`), or the samples' own, each in its sample's package: `NotesTools`,
`AskBeforeSavingSecrets`, `NoteStatsPlugin`, `ModelChoice`, `transcript()` and `lastAnswer()` in the notes app;
`TimerAgent`, `TimerTools`, `TimerScript` and `timerConfig()` in the timer; `HabitsAgent`, `HabitsTools`, `RESET_TOOL`,
`HabitListPlugin`, `HabitsScript` and `habitsConfig()` in the habits app.

## A first agent in one file

The host installs the server in `Application.onCreate`, before any screen or service runs:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/NotesApplication.kt first-agent -->
```kotlin
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
```

`builtModel()` reads three `BuildConfig` fields the notes build makes from Gradle properties, empty unless given
(`./gradlew :samples:agent-app-sample-notes:assembleDebug -PnotesModelBaseUrl=https://api.example.com/v1 -PnotesModel=example-chat -PnotesModelKey=…`).
At the top of `build.gradle.kts`:

<!-- quote: samples/agent-app-sample-notes/build.gradle.kts build-model-property -->
```kotlin
/** Gradle property [name] as a Java string literal for buildConfigField; empty when it is not set. */
fun gradleString(name: String): String =
    "\"" + (findProperty(name) as String?).orEmpty().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

in `defaultConfig { }`, with `buildFeatures { buildConfig = true }` in `android { }`:

<!-- quote: samples/agent-app-sample-notes/build.gradle.kts build-model -->
```kotlin
// The model service the build carries (NotesApplication's builtModel); with buildFeatures.buildConfig on.
buildConfigField("String", "MODEL_BASE_URL", gradleString("notesModelBaseUrl"))
buildConfigField("String", "MODEL_NAME", gradleString("notesModel"))
buildConfigField("String", "MODEL_KEY", gradleString("notesModelKey"))
```

What a host says in `AppServerConfig`, the common part (every field but `name` has a default):

| Field | What it is | Default |
|---|---|---|
| `name` | The server's name to clients (`serverInfo.name`), and of its debug sockets | required |
| `persona` | `Persona(name, identity, language)`: what the person calls the assistant, the line its system prompt opens with, and the language it answers in (a BCP 47 tag; `null` answers in the person's language) | `Persona.GENERIC` ("Assistant") |
| `tools` | Your tool providers, built for each run from the app's `Context` ([Tools](#tools)) | none |
| `rules` | Lines for the system prompt, in the persona's language. The prompt is cached across runs, so keep them the same from run to run | none |
| `skills` | Skills you ship, built from the app's `Context` (`(Context) -> List<RuntimeSkill>`; `RuntimeSkill(id, label, description, assetPath, enabled)`: `assetPath` is its `SKILL.md` in your assets, `enabled` whether it is on at first); the model reads one when it needs it, or every run with `preload = true`, and the person can switch each off | none |
| `hooks` | Gates on every tool call (`RuntimeHook`): allow, deny, or ask the person first ([Approvals](#approvals)) | none |
| `unaskedTools` | Full names of your tools that act but need no asking (`mcp__notes__add_note`), or `write_file`, `edit_file`, `exec_js` ([Approvals](#approvals)) | none: every tool that acts asks |
| `model` | A model service your build carries (`ModelPreset`, below) | none: the person connects one |
| `approvals` | The approval mode a new installation starts in ([Approvals](#approvals)). A mode a client sets (`run/set`) is the person's and is kept across restarts; `AppServer.resetApprovals()` (main thread) forgets it, going back to this one | `ASK_EVERY_TIME` |
| `simulatedModel`, `simulatedBackend` | For tests and demos: a new model for each run of the simulated backend (a `ScriptedModel`), and whether the server starts on that backend ([Testing offline](#testing-offline-with-a-scripted-model)) | the server's own, which only echoes; off |
| `builtIns` | The server's own features you turn on ([Off by default](#what-is-off-by-default)) | none |
| `plugins` | Plugins your build carries ([Plugins](#plugins)) | none |
| `debugSockets`, `diagnostics` | For your developers: local sockets to the protocol, and files on what each run did ([Off by default](#what-is-off-by-default)) | off |

**`ModelPreset(baseUrl, model, apiKey, endpoint, label)`**: `baseUrl` is an OpenAI-compatible `…/v1` (or Anthropic's),
`endpoint` is `ProviderEndpoint.CHAT_COMPLETIONS` (the default), `RESPONSES`, or `MESSAGES` for Anthropic, and `apiKey`
may be blank for a server on the device itself. It becomes a profile named `label` among the person's model profiles,
the one a new installation answers with, with no client call. A profile the person picks or adds later wins and is
saved; they can also edit this one's key and model. The server never shows, logs or publishes the key, but a key built
into an APK is readable by anyone who has the APK: an app people install gets its key from its own server or from the
person. Without a `model` (and no simulated backend), a turn ends with an error saying the model service has no API
key, until a client connects one (below).

Its tool provider, which the model reaches as `mcp__notes__add_note`, `mcp__notes__list_notes` and `mcp__notes__clear_notes`:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/NotesTools.kt tools -->
```kotlin
import android.content.Context
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's own tools: notes kept as lines of a file in the app's storage. The model reaches them as
 * `mcp__notes__add_note`, `mcp__notes__list_notes` and `mcp__notes__clear_notes`. The two that change the notes say so
 * (`MUTATING`) and so ask before they run, unless the config names them; the one that only reads says so (`READ_ONLY`)
 * and never asks.
 */
class NotesTools(context: Context) : ToolProvider {
    private val file = File(context.filesDir, "notes.txt")

    override val providerId: String = "notes"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "add_note",
            description = "Save one note for the user.",
            inputSchemaJson = """{"type":"object","properties":{"text":{"type":"string","description":"The note."}},"required":["text"]}""",
            effectKind = "MUTATING",
        ),
        ToolDescriptor(
            name = "list_notes",
            description = "List every saved note, oldest first.",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            effectKind = "READ_ONLY",
        ),
        ToolDescriptor(
            name = "clear_notes",
            description = "Delete every saved note. The user is asked first.",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            effectKind = "MUTATING",
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult = when (toolName) {
        "add_note" -> {
            val text = JSONObject(argumentsJson).optString("text").trim()
            if (text.isEmpty()) {
                failed("text is required")
            } else {
                synchronized(file) { file.appendText(text.replace('\n', ' ') + "\n") }
                ToolCallResult(success = true, resultJson = JSONObject().put("saved", text).toString())
            }
        }
        "list_notes" -> ToolCallResult(success = true, resultJson = JSONObject().put("notes", JSONArray(notes())).toString())
        "clear_notes" -> {
            val cleared = synchronized(file) { notes().size.also { file.delete() } }
            ToolCallResult(success = true, resultJson = JSONObject().put("cleared", cleared).toString())
        }
        else -> failed("unknown tool $toolName")
    }

    private fun notes(): List<String> = synchronized(file) { if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList() }

    private fun failed(message: String) = ToolCallResult(success = false, resultJson = JSONObject().put("error", message).toString())
}
```

## A screen that talks to it

A client gets its end of an in-process connection from `AppServer.connect(context)` and wraps it in a `ServerClient`
with a name, what it takes on (`ClientCapabilities`: answering approvals, here) and a coroutine scope:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/MainActivity.kt client -->
```kotlin
client = ServerClient(
    AppServer.connect(this),
    ClientInfo(name = "notes-screen", version = "0.1"),
    ClientCapabilities(approvals = true),
    scope = scope,
)
// A tool that waits for a yes is asked here; the first client to answer decides.
client.approvals = { request -> askPerson(request) }
scope.launch {
    client.connect()
    client.state.collect { state ->
        transcript.text = state.transcript()
        status.text = if (state.isRunning) getString(R.string.running) else state.providerLabel
    }
}
send.setOnClickListener {
    val text = input.text.toString().trim()
    if (text.isEmpty()) return@setOnClickListener
    input.text.clear()
    scope.launch { runCatching { client.request(ClientRequests.TURN_START, TurnStartParams(text)) }.onFailure { status.text = it.message } }
}
```

- `connect()` shakes hands and returns the server's name and features (`serverInfo.features`: which built-in features
  and plugins are on) and a snapshot; the first client to connect starts the server.
- `state` is a `StateFlow<ServerState>`: `items` (the current thread's `ConversationItem`s), `isRunning`,
  `providerLabel` (the model profile in use), `confirmationMode`, `simulated`, `lastRun`, `sessions` and more. Items
  are `TurnHeader` (the person's words), `MessageItem` (`role` `ASSISTANT`, with `final` on the answer; `THINKING`,
  `STATUS`, `RUNTIME`, `NOTICE`, `ERROR`), `ToolCallItem` (`toolName`, `argumentsJson`, `resultJson`, `status`),
  `ConfirmationItem` (an approval) and `RuntimePhaseItem`.
- `notifications` is every notification as it came (`ServerNotifications.TURN_COMPLETED` tells a turn ended).
- `request(method, params)` calls a method. The ones a simple client needs:

| Method | Params | What it does |
|---|---|---|
| `ClientRequests.TURN_START` | `TurnStartParams(text)` | Starts a turn in the current thread |
| `ClientRequests.TURN_STEER` | `TextParams(text)` | Adds the person's words to the running turn |
| `ClientRequests.TURN_INTERRUPT` | `TurnInterruptParams()` | Stops the current thread's run |
| `ClientRequests.THREAD_START`, `THREAD_LIST`, `THREAD_SELECT`, `THREAD_DELETE` | `Empty`, `ThreadIdParams(id)` | Conversations: a new one (which becomes the current thread), the list, another one, deleting one |
| `ClientRequests.APPROVAL_RESPOND` | `ApprovalRespondParams(pendingId, decision)` | Answers an approval from its `ConfirmationItem` (a client without an `approvals` handler) |
| `SettingsRequests.RUN_SET` | `RunSettingsParams(confirmationMode, simulatedBackend)` | The approval mode, the backend |
| `SettingsRequests.PROVIDER_CREATE`, `MODEL_SELECT` | `ProviderCreateParams(…)`, `ModelSelectParams(profileId)` | Adds a model profile, picks one |
| `PluginRequests.LIST`, `SET` | `Empty`, `PluginSetParams(id, enabled)` | Lists the plugins, turns one off or on |

When a tool waits for a yes, a client that took on approvals is asked; the first client to answer decides, and the
others' asks are called off:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/MainActivity.kt approval -->
```kotlin
/** Asks the person about a tool call that waits for a yes; another client answering first closes the dialog. */
private suspend fun askPerson(request: ApprovalRequest): ApprovalDecision = suspendCancellableCoroutine { answer ->
    val dialog = AlertDialog.Builder(this)
        .setMessage(getString(R.string.approve_tool, request.toolName.substringAfterLast("__")))
        .setPositiveButton(R.string.allow) { _, _ -> answer.resume(ApprovalDecision.APPROVE) }
        .setNegativeButton(R.string.deny) { _, _ -> answer.resume(ApprovalDecision.DENY) }
        .setCancelable(false)
        .show()
    answer.invokeOnCancellation { dialog.dismiss() }
}
```

Reading the state as text:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/Agent.kt transcript -->
```kotlin
/** The current thread as text: the person's words, the answers and what went wrong. */
fun ServerState.transcript(): String = items.mapNotNull { item ->
    when (item) {
        is ConversationItem.TurnHeader -> "> ${item.fullText}"
        is ConversationItem.MessageItem -> when (item.role) {
            TranscriptRole.ASSISTANT -> item.content.takeIf { item.final }
            TranscriptRole.ERROR -> "! ${item.content}"
            else -> null
        }
        else -> null
    }
}.joinToString("\n\n")

/** The last turn's answer, or what went wrong. */
fun ServerState.lastAnswer(): String? = items.asReversed().firstNotNullOfOrNull { item ->
    (item as? ConversationItem.MessageItem)?.takeIf { it.role == TranscriptRole.ERROR || (it.role == TranscriptRole.ASSISTANT && it.final) }?.content
}
```

Letting the person connect another model (a profile of their own, which wins over the build's `model`):

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/Agent.kt use-model -->
```kotlin
/**
 * Has the server answer with [choice] from now on: a profile of the person's own, which wins over the one the build
 * carries, also after the app restarts.
 */
suspend fun ServerClient.use(choice: ModelChoice) {
    val created = request(
        SettingsRequests.PROVIDER_CREATE,
        ProviderCreateParams("Notes model", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, choice.apiKey, choice.model, choice.baseUrl),
    )
    request(SettingsRequests.MODEL_SELECT, ModelSelectParams(profileId = created.profileId))
}
```

## Running a turn from code

A front end with no screen (a service, a receiver, a test) starts a turn and waits for its answer. The habits app does
it in its one client, quoted whole: one turn and its answer (`ask`), a new conversation, a plugin turned off or on, and
the approvals it answers in code ([below](#answering-approvals-in-code)):

<!-- quote: samples/agent-app-sample-habits/src/main/java/cc/noharry/agent/appserver/sample/habits/HabitsAgent.kt agent -->
```kotlin
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PluginRequests
import cc.noharry.agent.appserver.protocol.PluginSetParams
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.server.AppServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tally's one client of the server, for the service and the test: one request at a time, each a turn (whose answer it
 * returns), a new conversation, or a plugin turned off or on. With no screen to ask the person on, it answers in code
 * the approvals a turn asks for: forgetting a habit only when the request said so, anything else no.
 */
object HabitsAgent {
    const val TAG = "HabitsAgent"

    /** Where requests run: the main thread, as the server's own work does. */
    val scope = MainScope()
    private val requests = Mutex()
    private var client: ServerClient? = null
    private var resetAllowed = false

    /** One turn for [text]: its answer, or what went wrong. A reset it asks for is allowed only when [allowReset]. */
    suspend fun ask(context: Context, text: String, allowReset: Boolean = false): String = requests.withLock {
        val client = connected(context)
        resetAllowed = allowReset
        // Listening before the turn starts, so its end cannot pass unseen.
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(text))
        if (withTimeoutOrNull(TURN_MS) { ended.await() } == null) {
            ended.cancel()
            return@withLock "no answer within ${TURN_MS / 1000} s"
        }
        client.state.value.lastAnswer() ?: "no answer"
    }

    /** Starts a new conversation: its turns see none of the earlier ones. */
    suspend fun newConversation(context: Context) {
        requests.withLock { connected(context).request(ClientRequests.THREAD_START, Empty) }
    }

    /** Turns plugin [id] on or off; the choice is kept on the device. */
    suspend fun setPlugin(context: Context, id: String, on: Boolean) {
        requests.withLock { connected(context).request(PluginRequests.SET, PluginSetParams(id, on)) }
    }

    private suspend fun connected(context: Context): ServerClient = client ?: ServerClient(
        AppServer.connect(context.applicationContext),
        ClientInfo(name = "habits", version = "0.1"),
        // It takes on approvals: a call that waits for a yes is asked here.
        ClientCapabilities(approvals = true),
        scope = scope,
    ).also {
        it.approvals = { request -> decide(request) }
        it.connect()
        client = it
    }

    private fun decide(request: ApprovalRequest): ApprovalDecision {
        val allowed = request.toolName == RESET_TOOL && resetAllowed
        Log.i(TAG, "approval: ${request.toolName} ${if (allowed) "allowed" else "refused"}")
        return if (allowed) ApprovalDecision.APPROVE else ApprovalDecision.DENY
    }

    private const val TURN_MS = 50_000L
}

/** The last turn's answer, or what went wrong in it; null before any. */
fun ServerState.lastAnswer(): String? = items.drop(items.indexOfLast { it is ConversationItem.TurnHeader }.coerceAtLeast(0))
    .filterIsInstance<ConversationItem.MessageItem>()
    .lastOrNull { it.role == TranscriptRole.ERROR || (it.role == TranscriptRole.ASSISTANT && it.final) }?.content
```

- One `ServerClient` serves the process, connected on first use; the server starts with it. The `Mutex` keeps two
  requests (two service starts, say) from interleaving.
- It listens for `turn/completed` before `turn/start` goes out (`CoroutineStart.UNDISPATCHED`), so a quick turn cannot
  end unseen, and gives up after a time of its own: a turn that waits for an approval nobody answers takes a minute.
- The answer is in the state, not in the notification: `lastAnswer()` takes the items after the turn's `TurnHeader`
  and returns the final assistant message, or the error the turn ended with.
- The notes app's service does the same inline, and the timer's `TimerAgent.ask` (in `TimerRequestReceiver.kt`) also
  logs each tool call as it ends, from `item/updated` notifications.

## A service with no screen

A client needs no screen. The notes app also takes requests in a foreground service:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/AgentService.kt service -->
```kotlin
/**
 * The assistant with no screen: a foreground service that takes one request per start (extra `prompt`) and writes the
 * answer to the log under the tag NotesAgent. It answers no approval itself: a call that asks waits for the screen, if
 * one is open, and is not done when nobody answers within a minute.
 *
 *     adb shell am start-foreground-service -n <package>/.AgentService --es prompt "note that the meeting is at 3"
 */
class AgentService : Service() {
    private val scope = MainScope()
    private var client: ServerClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification())
        val prompt = intent?.getStringExtra("prompt")?.trim().orEmpty()
        scope.launch {
            val answer = runCatching { answer(prompt) }.getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "answer: $answer")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun answer(prompt: String): String {
        if (prompt.isEmpty()) return "no prompt"
        val client = client ?: ServerClient(AppServer.connect(this), ClientInfo(name = "notes-service", version = "0.1"), scope = scope)
            .also { it.connect(); client = it }
        // Listening before the turn starts, so its end cannot pass unseen.
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(prompt))
        ended.await()
        return client.state.value.lastAnswer() ?: "no answer"
    }

    override fun onDestroy() {
        client?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setContentTitle(getString(R.string.service_working)).setSmallIcon(android.R.drawable.stat_notify_sync).build()
    }

    private companion object {
        const val TAG = "NotesAgent"
        const val CHANNEL = "requests"
        const val NOTIFICATION = 1
    }
}
```

- A started service must call `startForeground` within a few seconds, with a notification on a channel (API 26+), and
  declare its type: `android:foregroundServiceType` with `FOREGROUND_SERVICE` and the type's own permission
  (`FOREGROUND_SERVICE_DATA_SYNC` here; required from API 34). From API 33 the notification shows only after the person
  grants `POST_NOTIFICATIONS`; the service runs either way. Android 15 limits `dataSync` services to six hours a day.
- An app that has never shown an Activity is always "in the background": from API 26 nothing outside can start a
  plain service in it (`am start-service` answers "app is in background"); a foreground service or a broadcast still
  can. The habits app has no Activity and takes its requests in such a service (`HabitsService`).
- `AppServer.wakeUp(context)` starts the server with no client (the plugins' and the host's own work resumes).
- A service that answers no approvals leaves them to another client. With nobody to answer, the call waits a minute and
  is not done. A host with no screen at all either allows everything (`approvals = ConfirmationMode.ALLOW_ALL_SESSION`,
  as the timer does) or answers in code, as the habits app does.

The timer app has no Activity and no service: a broadcast receiver runs one turn per intent. A receiver that calls
`goAsync()` must call `finish()` before the broadcast's time is up (about 10 s for a foreground broadcast, 60 s for a
background one, after which the system reports the app not responding), so its turns must be short:

<!-- quote: samples/agent-app-sample-timer/src/main/java/cc/noharry/agent/appserver/sample/timer/TimerRequestReceiver.kt receiver -->
```kotlin
/**
 * The app's only way in:
 * `adb shell am broadcast --include-stopped-packages -n cc.noharry.agent.appserver.sample.timer/.TimerRequestReceiver -e text "…"`
 * (an app never opened is "stopped" and gets no broadcast without the flag).
 * Each intent runs one turn and logs under the tag TimerAgent each tool call as it ends, then the answer.
 *
 * A receiver rather than a service: an app with no Activity is always "in the background", so from API 26 nothing
 * outside can start a plain service in it (`am start-service` answers "app is in background"), and a foreground
 * service needs a notification and a permission this app has no use for. The cost is the receiver's time limit: the
 * turn must end inside the broadcast's window (about 60 s for a background broadcast), so [TimerAgent] gives up at 50.
 */
class TimerRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        if (text.isEmpty()) {
            Log.w(TAG, "no 'text' extra; nothing to do")
            return
        }
        val pending = goAsync()
        TimerAgent.scope.launch {
            try {
                Log.i(TAG, "answer: ${TimerAgent.ask(context.applicationContext, text)}")
            } finally {
                pending.finish()
            }
        }
    }
}
```

The receiver is exported for adb, and the timer runs its tools in "allow all", so its manifest gives it
`android:permission="android.permission.DUMP"`, as the notes and habits apps give their services: only a sender holding
`DUMP` (adb's shell) reaches it, not another app.

### Answering approvals in code

A client answers approvals when it says so in the handshake and has a handler:

- **The capability**: `ClientCapabilities(approvals = true)`, the third argument of `ServerClient`. A client without it
  is never asked.
- **The handler**: `client.approvals = { request -> … }`, a suspending function from `ApprovalRequest` to
  `ApprovalDecision.APPROVE` or `DENY` (or null, which leaves it to another client). The request names the tool
  (`toolName`, its full name, and `argumentsJson`), the approval mode (`mode`), the conversation (`threadId`), the site
  for a web fetch (`domain`), the pending approval (`pendingId`, `itemId`) and `alwaysAsk`: true when the call asks
  every time whatever the mode (a plugin's tool that acts), so a yes covers this call alone; a screen then offers no
  "always allow" and no switch to allowing all. The `ConfirmationItem` in the conversation carries the same
  `alwaysAsk`. The habits app allows a reset only when the request that started the turn said so
  (`HabitsAgent.connected` and `decide`, above), and refuses anything else.
- **The approval mode** must be one that asks: `ASK_EVERY_TIME` (the default) asks each time, `ALLOW_CONFIRMED_DOMAINS`
  once per tool in a conversation ([which calls ask](#approvals)).
- **What the model reads on a no** (and in "deny all", which refuses without asking): the call does not run, and its
  result is `{"ok":false,"code":"CONFIRMATION_DENIED","message":"The user declined this action; the tool was not run."}`.
  The turn goes on; the habits script answers "Not done: forgetting a habit was not allowed."
  ([its test](#testing-offline-with-a-scripted-model)).
- With several clients, the first to answer decides. A client without a handler can still answer from the
  `ConfirmationItem` with `APPROVAL_RESPOND`; an approval nobody answers expires after a minute and the call is not
  done.

## The model's side

A model the person connects needs nothing from you. What follows matters when you write a `ModelClient` of your own,
or read why a run did what it did; `ScriptedModel` (next section) does all of it for you.

- **The request** (`ModelRequest`): `system` (the persona's line, the server's rules, your `rules`, then plugins'
  rules), `tools` (the server's own only: `search_tools`, `call_tool`, `finish`, `ask`, `ask_async`, `update_todo`,
  `list_dir`, `read_file`, `grep`, and those of features you turned on) and `messages`, whose `role` is a plain string:
  `"user"`, `"assistant"`, `"tool"` (a tool's result, with the call's `toolCallId`), rarely `"system"`.
- **Your tools are not in the tool list.** The model finds them with `search_tools {"query": "...", "max_results": 10}`,
  whose result lists matches as `{"name": "mcp__notes__add_note", "originalName": "add_note", "description": ...,
  "via": "call_tool", "schema": {...}}`, and calls one with `call_tool {"name": "mcp__notes__add_note", "arguments":
  {"text": "..."}}`. A tool's full name is `mcp__<providerId>__<tool name>` (characters other than letters, digits, `_`
  and `-` become `_`; a name over 64 characters is cut and ends in a hash).
- **Tools found before.** A tool a conversation found stays loaded for its later turns: the model calls it with
  `call_tool` without searching again, reading its schema from the earlier search. After the app's process restarts,
  the conversation still has the tools its earlier turns show and calls them without searching. While a tool's server
  is off, a call is refused as `TOOL_UNAVAILABLE` ([Plugins](#plugins)) and the tool stays the conversation's: once the
  server is back, it runs again.
- **Messages from the app.** After the person's words a request may carry `"user"` messages that start with
  `From the app, not typed by the user:`: the run's context (the time, the app in front) and, when the run has loaded
  tools whose schema the conversation does not show (picked for the request, or shown in turns since summarized),
  "tools ready for this request" with their schemas, callable with `call_tool`. They are not the person's words.
- **A tool's result** reaches the model as its `resultJson`, unchanged, when `success` is true. A failed call
  (`success = false`) reaches it as `{"ok":false,"code":"BACKEND_CALL_FAILED","message":"<your resultJson, as text>",
  "retryable":<retryable>}`, unless your `resultJson` is already `{"ok":false,"code":"<YOUR_CODE>",...}` (with no
  `error` member), which reaches it as written. A provider that throws gives `BACKEND_CALL_FAILED` with the exception's
  message. A call that does not run reaches it as the server's own `{"ok":false,"code":...,"message":...}`: a hook's
  denial as the hook's reason, a no as `CONFIRMATION_DENIED` ([above](#answering-approvals-in-code)), a tool whose
  server is off now (its plugin off, switched off in the MCP list, unreachable or removed) as `TOOL_UNAVAILABLE` with a
  message saying so ([Plugins](#plugins)), a tool not loaded in this run as `INACTIVE_BACKEND_TOOL`, a name no server
  of the run has as `UNKNOWN_BACKEND_TOOL`.
- **The end of a turn**: `finish {"response": "...", "outcome": "success"}` (`outcome` is `success`, `partial` when
  part of the request was not done, or `blocked` when something stands in the way). A partial or blocked reply is an
  answer, not a failed run: `turn/completed` says `partial` or `blocked`, or `completed` when the reply asks the person
  something, as a question does; over A2A the task is `completed`, `rejected` (blocked, asking nothing) or
  `input-required`. A turn with no reply, a broken tool loop, or a claimed success with a required action left undone
  fails. A reply of plain text with no tool call ends the turn too. `ask {"question": "..."}` pauses the run for the
  person's answer.
- **The reply** is a flow of `ModelEvent`s: `TextDelta`, `ThinkingDelta`, `ToolCall(id, name, argumentsJson)`,
  `UsageDelta`, then `Completed`.

## Testing offline with a scripted model

`ScriptedModel` plays a script written in your own terms: when the person says something, call these of your tools
with these arguments, then answer with text made from their results. The timer's script:

<!-- quote: samples/agent-app-sample-timer/src/main/java/cc/noharry/agent/appserver/sample/timer/TimerScript.kt script -->
```kotlin
/**
 * What Pip does with no model service: the script the simulated backend plays, offline, in the tests and on a device.
 * "start a 5 minute egg timer", "list my timers", "cancel the egg timer".
 */
val TimerScript = ScriptedModel {
    on(Regex("""(?:cancel|stop)\s+(?:the\s+)?([a-z ]+?)\s*(?:timer|$)""")) { found ->
        call("timer", "cancel_timer", mapOf("label" to found.groupValues[1]))
        answer { results -> sentence(results.single()) }
    }
    on("list") {
        call("timer", "list_timers")
        answer { results -> sentence(results.single()) }
    }
    on(Regex("""(\d+)[\s-]*(hour|minute|second)s?\s+([a-z ]*?)\s*timer""")) { found ->
        val (amount, unit, label) = found.destructured
        val seconds = amount.toLong() * mapOf("hour" to 3600, "minute" to 60, "second" to 1).getValue(unit)
        call("timer", "start_timer", mapOf("label" to label.ifBlank { "kitchen" }, "seconds" to seconds))
        answer { results -> sentence(results.single()) }
    }
    otherwise { answer("I'm Pip, the kitchen timer. Ask me to start, list or cancel a timer.") }
}
```

- `on(words)` matches words the person's message contains (ignoring case), `on(regex)` a pattern (the turn gets the
  match), `otherwise` everything else; the first that matches plays.
- `call(provider, tool, arguments)` takes the provider's `providerId`, the tool's name and its arguments as a map (or
  JSON text); calls run in order.
- `answer(text)` ends the turn. `answer { results -> ... }` makes the text from the calls' results: `results` is a
  `List<String>`, one per `call` in the order written, each the text the model was given for it
  ([above](#the-models-side)): your `resultJson` when the call succeeded, otherwise the server's JSON. The habits
  script reads both kinds:

<!-- quote: samples/agent-app-sample-habits/src/main/java/cc/noharry/agent/appserver/sample/habits/HabitsScript.kt results -->
```kotlin
/**
 * One sentence from what the model was given for a call: the tool's resultJson when it succeeded; otherwise the
 * server's JSON, `{"ok":false,"code":…,"message":…}`, whose code says why: BACKEND_CALL_FAILED (the tool failed; its
 * own resultJson is the message), CONFIRMATION_DENIED (nobody said yes, so it did not run), TOOL_UNAVAILABLE (its
 * server or plugin is off, so it did not run).
 */
private fun sentence(given: String): String {
    val result = JSONObject(given)
    if (result.optBoolean("ok", true)) return done(result)
    val message = result.optString("message")
    return when (result.optString("code")) {
        "BACKEND_CALL_FAILED" -> "That didn't work: " + (runCatching { JSONObject(message).getString("error") }.getOrNull() ?: message) + "."
        "CONFIRMATION_DENIED" -> "Not done: forgetting a habit was not allowed."
        "TOOL_UNAVAILABLE" -> "Not done: that is turned off right now."
        else -> "Not done: " + result.optString("code") + "."
    }
}
```

- It speaks the model's side for the script: a tool the conversation was shown before (in a search's matches or the
  tools-ready list) is called with `call_tool` straight away; any other is searched for first; one the run refuses as
  not loaded (`INACTIVE_BACKEND_TOOL`) is searched for and called again. A tool its search
  does not find ends the turn, with outcome `blocked`, saying `<full name> is not among the tools search_tools found;
  its provider may not be mounted.`; its `answer` is not used. It reads past the app's messages and ends with `finish`.
- It keeps nothing between requests, so one instance serves every run.

The host gives it to the simulated backend, and here starts on it, so the first turn plays the script with no model
service:

<!-- quote: samples/agent-app-sample-timer/src/main/java/cc/noharry/agent/appserver/sample/timer/TimerApplication.kt config -->
```kotlin
/** Pip, a kitchen timer with no screen and no model service: its persona, its tools and rule, and the script it plays. */
fun timerConfig(): AppServerConfig {
    val timers = TimerTools()
    return AppServerConfig(
        name = "agent-app-sample-timer",
        persona = Persona(
            name = "Pip",
            identity = "You are Pip, a kitchen timer assistant running on the user's device. You start, list and " +
                "cancel cooking timers through your tools and answer in one short sentence.",
            language = "en",
        ),
        tools = { listOf(timers) },
        rules = listOf(MAX_TIMER_RULE),
        // No screen to answer on: nothing waits for a yes.
        approvals = ConfirmationMode.ALLOW_ALL_SESSION,
        // No model service: every turn plays the script, from the first one on.
        simulatedModel = { TimerScript },
        simulatedBackend = true,
    )
}

class TimerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(this, timerConfig())
    }
}
```

A client switches backends with `run/set {simulatedBackend}`; picking or adding a model profile switches to it.

A test runs the turns through the protocol under Robolectric, with no device and no network, as the app's own clients
do: Robolectric starts the app's `Application`, which installs the server. The habits app's test, whole (the timer's
`TimerAgentTest` is the same with one method):

<!-- quote: samples/agent-app-sample-habits/src/test/java/cc/noharry/agent/appserver/sample/habits/HabitsAgentTest.kt test -->
```kotlin
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tally's turns, offline: the app's own [HabitsApplication] installs the server with its script, and each turn goes
 * in and comes out through the protocol, as the service's do on a device.
 *
 * The methods share one server, started by the first to run: Robolectric gives each method a new Application, which
 * installs the config again, but the server is one per process and keeps working with the first Application, its
 * files and conversations (the current one too), and JUnit picks the order. So each method starts a conversation of
 * its own, turns the plugin on and uses habits no other method does.
 */
@RunWith(RobolectricTestRunner::class)
class HabitsAgentTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aConversationOfItsOwn() = onMain {
        HabitsAgent.newConversation(app)
        HabitsAgent.setPlugin(app, HabitListPlugin.id, true)
    }

    @Test
    fun loggingTwiceOnOneDayCountsOneDay() {
        assertEquals("Logged running; streak 1.", ask("log running"))
        assertEquals("Logged running; streak 1.", ask("log running"))
        assertEquals("Streak for running: 1.", ask("streak for running"))
    }

    @Test
    fun aResetNobodyAllowedChangesNothing() {
        ask("log swimming")
        // HabitsAgent refuses the approval; the model is told CONFIRMATION_DENIED and the tool does not run.
        assertEquals("Not done: forgetting a habit was not allowed.", ask("reset swimming"))
        assertEquals("Streak for swimming: 1.", ask("streak for swimming"))
        assertEquals("Reset swimming.", ask("reset swimming", allowReset = true))
        assertEquals("Streak for swimming: 0.", ask("streak for swimming"))
    }

    @Test
    fun aPluginsToolWorksAgainInTheConversationThatCalledItWhileItWasOff() {
        ask("log reading")
        assertTrue(ask("list my habits").contains("reading"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, false) }
        // The conversation found the tool before: the call is refused as TOOL_UNAVAILABLE, its server being off.
        assertEquals("Not done: that is turned off right now.", ask("list my habits"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, true) }
        assertTrue(ask("list my habits").contains("reading"))
    }

    @Test
    fun aConversationThatNeverFoundThePluginsToolFindsItOnceThePluginIsOn() {
        // Off from the start: no search of this conversation shows the plugin's tool.
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, false) }
        ask("log cycling")
        // Its search does not find the tool: the script ends the turn saying so.
        assertTrue(ask("list my habits").contains("mcp__habit_list__list_habits is not among the tools search_tools found"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, true) }
        assertTrue(ask("list my habits").contains("cycling"))
    }

    /** One turn. */
    private fun ask(text: String, allowReset: Boolean = false): String = onMain { HabitsAgent.ask(app, text, allowReset) }

    /** [block] on the main thread, which the test is: the main looper is turned until it is done. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.Main).async { block() }
        val until = System.currentTimeMillis() + WAIT_MS
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            // Its clock moves only when told: a delay on the main thread waits for this.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return running.getCompleted()
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val IDLE_MS = 5L
    }
}
```

- **The main thread.** The test runs on the main thread, which the server also uses, so it turns the main looper while
  it waits (`onMain`), moving its clock (`idleFor`) so a `delay` on the main thread ends.
- **Several `@Test` methods in one class** work, as above, with care. Robolectric gives each method a new
  `Application`, whose `onCreate` installs the config again, but the server is one per process: the one the first
  method started keeps working, for every later method, with that method's `Application`, its files and its
  conversations, the current one included; and JUnit picks the order. So each method starts a conversation of its own (`thread/start`),
  sets every switch it relies on, and uses data no other method touches; the habits test passes so in any order. A
  test that needs a server of its own goes in a class with an SDK level of its own (`@Config(sdk = [33])`): each
  SDK level is a sandbox of its own, with its own server.
- **Switching a plugin in a test** is a request like any other: `plugins/set` through the test's client
  (`HabitsAgent.setPlugin`). It takes effect from the next turn; what a conversation then sees is under
  [Plugins](#plugins).
- **What the model is offered** can be checked too ([below](#seeing-what-your-model-was-offered)).

## Tools

A tool provider (`cc.noharry.agent.spi.ToolProvider`) has a `providerId` (letters, digits, `_`; the middle of its tools'
full names), `listTools()` and `callTool(toolName, argumentsJson, context)`; `displayName` and `initialize` are
optional. Calls to one provider run one at a time unless it declares `providerConcurrency`. `tools` builds providers
for each run, so a provider may be made fresh each time (keep state elsewhere, as the notes do in a file) or be one
object the lambda returns (the timer's).

`ToolDescriptor(name, description, inputSchemaJson, title, effectKind, completionImpact, outputSchemaJson)`: the model
reads the name, the description and the input schema (a JSON Schema object; the runtime checks arguments against
it). `effectKind` is a string, one of these (any other string counts as `UNKNOWN`):

| `effectKind` | What it changes |
|---|---|
| `READ_ONLY` | The tool only reads: it never asks before it runs. Several such calls in one model round may run together. A plugin may watch the tool on a timer |
| `MUTATING` | The tool acts: a call asks before it runs, and the approval mode answers ([Approvals](#approvals)). With `completionImpact = "REQUIRED_ON_CALL"`, a call the model starts must succeed before the turn can end in success |
| `CONTROL` | The tool acts: a call asks first |
| `UNKNOWN` (default) | Not declared, which counts as acting: a call asks first |

**Say `READ_ONLY` for a tool that only reads**, or every call of it asks the person in the modes that ask.

`ToolCallResult(success, resultJson, retryable, trace, images)`: `resultJson` is what the model reads, so make it short JSON
the model can act on, including on failure (`{"error": "no timer named tea"}`); `success = false` wraps it as shown
[above](#the-models-side), and a tool that throws fails its call with the exception's message. `images` (a
screenshot, say) reach a model that reads pictures.

### Approvals

A call of a tool that does not say it only reads asks the person first, whoever brought the tool: you, a configured MCP
server (whose tool says so with `readOnlyHint`), a plugin, or the server itself (`write_file`, `edit_file`,
`exec_js`). The approval mode (`ConfirmationMode`) answers it: "ask every time" asks each time, "allow confirmed
domains" once per tool in a conversation, "allow all" runs it unasked and "deny all" refuses it unasked.

**A tool of yours that acts but needs no asking** (saving a note, logging a habit) is named in
`AppServerConfig.unaskedTools` by its full name, as the notes app does with `mcp__notes__add_note`; its `effectKind`
stays what it is, so it is still not run beside other reads or watched by a plan. `write_file`, `edit_file` and
`exec_js` may be named too. A plugin package's tool cannot be: it asks every time.

**A hook** adds rules of its own on any call: `HookDecision.RequireConfirmation` makes it ask (the mode answers, as
above), `HookDecision.Deny` refuses it in every mode, before anything asks. Your hooks run first and win over the
server's rule: a call one of them refuses is refused; one it asks about asks, even if `unaskedTools` names it.

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/AskBeforeSavingSecrets.kt hook -->
```kotlin
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolUse
import org.json.JSONObject

/**
 * A note that looks like a password or a code waits for the person's yes, although saving notes needs none
 * (`unaskedTools`): a hook's own rule adds to the server's. A hook that answers [HookDecision.RequireConfirmation] makes
 * any call ask, and the approval mode then decides as for every call that asks; one that answers [HookDecision.Deny]
 * refuses it in every mode. After a yes the same call comes back with `approvedConfirmation` set.
 */
object AskBeforeSavingSecrets : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (call.toolName != "mcp__notes__add_note" || context.approvedConfirmation) return HookDecision.Allow()
        val text = runCatching { JSONObject(call.argumentsJson).optString("text") }.getOrDefault("")
        return if (SECRET.containsMatchIn(text)) {
            HookDecision.RequireConfirmation(reason = "LOOKS_SECRET", prompt = "Save a note that looks like a password?")
        } else {
            HookDecision.Allow()
        }
    }

    private val SECRET = Regex("""password|passcode|\bpin\b|\b\d{6,}\b""", RegexOption.IGNORE_CASE)
}
```

What asks, by mode. `HostApprovalsTest` runs every row marked *pinned* in all four modes through the protocol (and
what the model reads on a no); `ActingToolGateTest` checks which calls ask, `write_file`, `exec_js` and the server's own
tools among them; the rest is read from the code.

| Call | `ASK_EVERY_TIME` (default) | `ALLOW_CONFIRMED_DOMAINS` | `ALLOW_ALL_SESSION` | `DENY_ALL` |
|---|---|---|---|---|
| Your tool that acts (`MUTATING`, `CONTROL`, or `UNKNOWN`: not declared) *pinned* | asks every time | asks once per tool per conversation | runs, unasked | refused, unasked |
| Your tool that only reads (`READ_ONLY`) *pinned* | runs | runs | runs | runs |
| Your tool that acts, named in `unaskedTools` *pinned* | runs | runs | runs | runs |
| Any call a hook answers `RequireConfirmation` for | asks every time | asks once per tool per conversation | runs, unasked | refused, unasked |
| Any call a hook answers `Deny` for *pinned* | refused | refused | refused | refused |
| `write_file`, `edit_file` (`builtIns.fileWriting`), `exec_js` (`builtIns.scripts`) | asks every time | asks once per tool per conversation | runs, unasked | refused, unasked |
| `web_fetch` (`builtIns.web`) | asks once per site per turn | asks once per site per conversation | runs | refused |
| `web_search`, reading the workspace, `search_tools`, `finish`, `ask`, `ask_async`, `update_todo`, `spawn_agent`, `wait_agent`, `cancel_agent` | runs | runs | runs | runs |
| A plugin package's tool that acts, a plugin's tools with `askBeforeActions` *pinned* | asks every time | asks every time | asks every time | refused, unasked |

A package's tool acts unless it says it only reads and its publisher is one the host trusts: an unknown publisher's
script tools, and every tool of its MCP servers whatever the remote server's `readOnlyHint` says, ask as the last row.
A configured MCP server's tools, and a plugin's tools, tool servers and MCP servers without `askBeforeActions`, are held
to the same rule as yours: an MCP server's tool only reads when it says `readOnlyHint`. A plugin's own tool server
says which of its calls act (`PluginContext.ownToolServer`); those ask, the others run.

A person's no, and "deny all", reach the model as `CONFIRMATION_DENIED`, a hook's `Deny` as `HOOK_DENIED`; the turn
goes on. Who answers, a person on a screen or code, is up to the clients ([Answering approvals in
code](#answering-approvals-in-code)). An approval nobody answers expires after a minute, and the model reads
`CONFIRMATION_EXPIRED`: a turn nobody watches (one a plugin delivers, a request from a service) does not wait
longer. Where nobody can be asked:

- **A subagent** inherits its turn's mode: in "allow all" its calls that act run; in the other modes they are refused
  (it cannot ask; the model reads that a subagent cannot request approval and puts what should be done in its result),
  and a plugin package's tool that acts is refused in every mode.
- **A turn a plugin delivers** (`PluginContext.deliver`: a timed task coming due, say) is a turn like the person's, in
  the mode of the moment: a call that acts asks, and expires after a minute if nobody answers. What a plugin does on its
  own without the model is the plugin's to hold to the person's mode (`PluginContext.approvalMode`).

## What is off by default

`BuiltInFeatures` (in `AppServerConfig.builtIns`), each off until you turn it on; one left off gives the model no tool,
no row on the basic tools page and no request of its own, and `serverInfo.features` names those that are on:

| Switch | What it brings |
|---|---|
| `fileWriting` | `write_file`, `edit_file` in the model's workspace |
| `web` | `web_search`, `web_fetch` and a model service's own search |
| `scripts` | `exec_js` (JavaScript in a sandbox), still off on the basic tools page until a client switches it on |
| `subagents` | `spawn_agent`, `wait_agent`, `cancel_agent` and the subagent settings |

On without a switch: reading the workspace (`list_dir`, `read_file`, `grep`), and the **lessons the runtime draws from
runs** (below), which are not the person's memory: `memory/lessons` lists them either way.

Anything more comes from [plugins](#plugins) a host lists. One not listed gives the model no tool, answers none of its
requests (a client sending one is told the method is not found), publishes no state and reads or writes no file;
listed, the person can turn it off and on while the app runs, which takes all of that back and brings it again. What it
keeps for conversations goes with them even while it is off (`AppServerPlugin.clearWhileOff`): a conversation deleted
then takes the plugin's part of it, and so does runtime data cleared.

Starting after a reboot or an update is no switch of its own: the server turns `BootReceiver` on while a running plugin
asks (`PluginContext.startOnBoot`: one that sets alarms, so they are set again), and off when none does. Your manifest
needs `RECEIVE_BOOT_COMPLETED` for it.

`debugSockets = true` (for your debug builds; the server does not look at whether the build is debuggable, so leave
it off in a release): the server also listens on two abstract local sockets, `<name>` (the protocol, one JSON message
per line; `adb forward tcp:8791 localabstract:<name>`) and `<name>-acp` (the Agent Client Protocol, for an editor),
which only your app, adb's shell and root may connect to. Whoever has the shell then has every request a client has:
`plugins/install` with a package's bytes and `plugins/confirm` with `acknowledgeUntrusted` install a package from a
publisher you do not trust, or an unsigned one where `allowUnsignedPackages` is on. Each prompt over the second runs on
a runtime built when it starts, as the app's own turns do, so it sees the plugins and settings of that moment, and the
rules on asking hold there too (its sessions' modes are the approval modes). Otherwise the server opens no
socket. `diagnostics = true`: files on each run that only grow until runtime data is cleared:
`files/runtime-debug-logs/` (each call to a model service, request and reply), `files/debug-run-evidence/` (each run's
events and replay check), `files/mcp-progressive-evidence/` (with the simulated backend: the tools of each run's first
request, those it searched for and called), `files/agent-reliability/`, `files/web-search-evidence/`.

What the server keeps on the device either way, all in your app's private storage:

- conversations (`files/runtime-debug-workspace/sessions/`) and their event logs (`no_backup/debug-session-event-log/`,
  every model request and reply, tool call and result);
- the model's workspace (`files/runtime-debug-workspace/`), with the
  **lessons the runtime draws from runs**, per scene: `memory/lessons/<scene, base64url>/` in it (`pending.json` for
  candidates, `lessons.json` for those later runs bore out), which the runtime consults in later runs of the scene;
- pictures the screens show (`files/generated-images/`: those a plugin drew, a folder per conversation; those another
  agent's report brought, a folder per task), deleted with their conversation whether or not the plugin runs;
- what your plugins keep, where they say; clearing runtime data takes a plugin's own data only when a client names the
  plugin (`data/clear {"also": ["memory"]}`: `DataClearParams.also` lists the plugins, by id);
- settings in `SharedPreferences` files named `app_server_*`: model profiles (`app_server_provider_config`, with the
  keys the person typed, not encrypted yet), MCP servers, skills' switches, which plugins are on
  (`app_server_plugins`), the approval mode the person chose (`app_server_approvals`). Keys of MCP servers and plugin
  packages are in `no_backup/` (a package's in `no_backup/app_server/plugin-secrets/`, as plain JSON);
- installed plugin packages, in `files/app_server/plugins/<id>/<version>/`.

Set `android:allowBackup="false"` unless you want those in the person's backups.

### Seeing what your model was offered

- **Every request, always**: the event log keeps each model request whole. On a debuggable build,
  `adb shell run-as <package> sh -c 'cat no_backup/debug-session-event-log/*/session-events/v2/streams/*/*/segments/*.jsonl' | grep '"type":"MODEL_REQUEST"'`
  gives one JSON line per request, whose `payload` has `systemPrompt`, `tools` (each tool's `name` and `schema`) and
  `messages`. A host that turns nothing on is offered `list_dir`, `read_file`, `grep`, `search_tools`, `update_todo`,
  `ask`, `finish`, `ask_async` and `call_tool` (the habits app, read so); your own tools and your plugins' are not in
  `tools`, they are found with `search_tools`.
- **With `diagnostics = true`**: `files/runtime-debug-logs/<request id>.log` has an entry per call to a model service;
  a `Provider request #n` entry says `toolNames=<count> toolNameList=[…]` (the first 16 names in the request) and
  `bodyPrefix=` (its first 4 KB, keys redacted). With the simulated backend, `files/mcp-progressive-evidence/<request
  id>.json` names the tools of the first request instead.
- **In a test**: give `simulatedModel` a `ModelClient` of your own that notes each request's `tools` (and `system`)
  and passes the request on to your `ScriptedModel`.

## Plugins

A plugin adds to the server from code of your own, and the person can turn it off and on while the app runs (the
choice is kept on the device). You write it against `agent-app-server` and list it in `AppServerConfig.plugins`; it may
be a class in your app or a module of its own, which then brings its manifest entries (a receiver, a service, a
permission) to the hosts that depend on it. No plugin is published with the server. The first-party ones (cards,
DeepSeek's extras, operating the screen, the device's tools, drawn pages, memory, pictures, plans, A2A) are source
modules of the Bothy app, built into it and not Maven artifacts; their source is published with Bothy's next
release. Until then, the plugin below and the habits app's are the examples to start from.

A plugin is an `AppServerPlugin` with an `id` and one `apply(ctx)`; everything it says to its `PluginContext` is taken
back when it stops:

<!-- quote: samples/agent-app-sample-notes/src/main/java/cc/noharry/agent/appserver/sample/notes/NoteStatsPlugin.kt plugin -->
```kotlin
import android.content.Context
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import org.json.JSONObject

/**
 * Counting the notes' words, as a plugin: unlike the config's own tools, the person can turn it off and on while the
 * app runs (`plugins/set {"id":"note-stats","enabled":false}`), and everything [apply] added goes with it.
 */
object NoteStatsPlugin : AppServerPlugin {
    override val id: String = "note-stats"

    override fun apply(ctx: PluginContext) {
        ctx.tools { context, _ -> listOf(NoteStats(context)) }
        ctx.rules(listOf("When asked how much is noted, count the words with the note statistics tool."))
    }
}

private class NoteStats(context: Context) : ToolProvider {
    private val file = File(context.filesDir, "notes.txt")

    override val providerId: String = "note_stats"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor("count_words", "Count the words in all saved notes.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val words = if (file.exists()) file.readText().split(Regex("""\s+""")).count { it.isNotBlank() } else 0
        return ToolCallResult(success = true, resultJson = JSONObject().put("words", words).toString())
    }
}
```

A `PluginContext` takes tools (`tools`; with `askBeforeActions = true`, for tools from outside your app, those not
described `READ_ONLY` ask every time, in every mode but "deny all"; without it they ask as yours do), rules
(after yours, behind a line saying plugin text cannot widen what the person allowed; 3 KiB for all plugins), skills,
MCP servers, tool servers the person switches in the MCP list, requests of its own (`routes`), state (`publish`), and
work of its own (`scope`, `onStop`). For work that goes on without the person it also has `startOnBoot` (the app starts
after a reboot while it runs), `deliver` (a turn the agent runs, or words said as written, into the conversation on
screen, queued with the server's own reports), `approvalMode`, `voice` and `describeTool` (your `RunAddOns` words on
its tools); `ownToolServer` names its tool server (one it gives runs, or one it lists in the MCP list), says which
calls act (by tool and arguments; `null` leaves a call to its tool's `effectKind`) and whether only the main agent may call it,
and `AppServerPlugin.clearWhileOff` deletes what it kept for conversations while it is off. `needs` names plugins it
needs; `required = true` keeps it always on. Clients see
plugins with `plugins/list` and switch them with `plugins/set`; `serverInfo.features` follows (`server/features`).

**Turned off and on while the app runs.** A plugin's requests come and go at once; what it gives runs (tools, tool
servers, MCP servers, rules, skills) changes from the next turn. While it is off:

- a new conversation's search does not find its tools;
- a conversation that found one of them before keeps it (the runtime keeps a conversation's tools while their
  provider is away), and a call is refused, the runtime answering `{"ok":false,"code":"TOOL_UNAVAILABLE","tool":
  "mcp__habit_list__list_habits","message":"This tool is not available now: the tool server or plugin that provides it
  is switched off, cannot be reached, or was removed. Searching for it or calling it again will not help until it is
  back; tell the person.","retryable":false}` (`ToolServerComesBackTest` pins it word for word).

Once it is on again, from the next turn every conversation calls its tools as before: one that found them earlier
straight away, without searching again, and a new one after its search. A tool server switched off and on in the MCP
list (a configured MCP server, or a plugin's tool server) is treated the same way. The habits test pins both
conversations for a plugin's tools (`aPluginsToolWorksAgainInTheConversationThatCalledItWhileItWasOff`,
`aConversationThatNeverFoundThePluginsToolFindsItOnceThePluginIsOn`), and `ToolServerComesBackTest` a plugin's tool
server.

**Plugin packages** are plugins a person downloads: a zip (`.agentplugin`) of a manifest, skills, rules, MCP server
addresses and tools written as JavaScript, signed by its publisher, with no compiled code. A host decides whom it trusts
(`trustedPublishers`, public keys; `allowUnsignedPackages` for a developer's own device) and a client installs one with
`plugins/install` then `plugins/confirm`. Packages are made with `tools/agent-plugin` (see its `README.md`; the design
is `docs/design/plugin-packages.md`). What a host should know before it lets people install them:

- **Asking.** A package's tools that act ask the person every time, in every mode but "deny all", which refuses them.
  A package from a publisher the host does not trust (or an unsigned one) is not taken at its word that a tool only
  reads: its script tools, and every tool of its MCP servers whatever the remote server's `readOnlyHint` says, ask
  before each call. A trusted publisher's tools that say they only read run unasked.
- **The network.** A package reaches only the hosts it names, each shown with where it is: the internet, the person's
  network, or this device. One that names `localhost` or `127.0.0.1` reaches every port on this device, your app's own
  local servers included. A script's request to a name on the internet that resolves into the device or the person's
  network is refused, and scripts' requests never go through a proxy (which would look the name up itself).
- **Trust roots.** The server's connections (model services, MCP servers, package downloads, scripts' requests) use its
  own trust manager: the system's roots and `AppServerConfig.extraTrustRoots`. Your `network_security_config` (its
  pins, its per-domain trust) does not apply to them.
- **Debug sockets.** With `debugSockets` on, anyone with adb's shell can install a package and acknowledge an
  unknown publisher for the person ([What is off by default](#what-is-off-by-default)).
- **Secrets** a person types for a package are kept as plain JSON in your app's no-backup storage, not encrypted.
- **Where they live.** `files/app_server/plugins/<id>/<version>/`, checked again against their digests and signature
  each time they start. A package whose files changed shows as damaged and does not start until it is installed again
  or removed; one whose record cannot be read, until it is removed.

## Going deeper (experimental)

For a host that needs more than plain values, `AppServerConfig` also takes: `presets`
(`ProviderPresets`: model services as a profiles file), `voice` (`VoicePort`: speech in and out), `models` (`HostModels`: models you run yourself, such as an on-device
one), `runs` (`RunAddOns`: what depends on each run: rules and gates that read `RunFacts`, context lines, loop settings,
tool wrappers, a spoken note on tool calls, subagent types), `replayCheck` (`ReplayCheck`: a bench's checks of finished
runs), `extraTrustRoots`, `scene`, `trustedPublishers`,
`allowUnsignedPackages` and `maxScriptToolSeconds`. Their KDoc is the reference.

A plugin that goes further uses the members of its `PluginContext` marked `@AppServerPluginApi` (its module opts in
with the compiler option `optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")`). One that works the conversation
itself (a voice, a companion, a debug door) has `turns` (`Turns`: start, replace or stop a turn of the conversation on
screen, follow the runtime's state, start a new conversation, keep a turn something else answered, add a line of your
own), `speech` (`Speech`: words the person said, an acknowledgement said first, a preview of the voice, silencing the
run going on), `assistant` (`Assistant`: the client presenting the assistant on this device, its wake and card, and
requests and notifications of your own to it), `outsideTurns` (turns for a client outside the app, such as another
agent's task) and `request` (any protocol request, answered as a client's would be, by the server's routes and the
running plugins'). The others shape runs and the server: `offer`/`offers` (features named to clients),
`providerAdapter` (what a model service offers beyond chat), `wrapTools`, `runTools`, `contextLines`,
`contextProviders`, `runHooks`, `skillSections`, `prepareInput` and `turnLabel` (what each run gets), `onLocalTurn`,
`onAppInFront`, `onPresenterReady` and `onClearData` (what happens on the device), and `provide`/`service` (services
plugins offer each other). Their KDoc is the reference. A host keeps nothing of the server's own objects: what it
needs outside its plugins it reaches through one of its plugins' contexts.

## What is experimental, and what is not done

- All of it is experimental for 0.5.0. What this guide names before "Going deeper" is the part meant for every host;
  the rest is for hosts that go deep and is likelier to change.
- The protocol's methods are not yet marked stable or experimental one by one.
- Keys the person types (model profiles, MCP servers, plugin packages' secrets) are kept unencrypted in your app's
  private storage.
- Answering the model's `ask` from a client (`question/answer`) is not shown by the samples.
