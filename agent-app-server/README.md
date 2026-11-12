# Agent app server: a guide for hosts

> **Experimental.** The app server and its protocol are modules of Android Agent Runtime, published as
> `cc.noharry:agent-app-server` and `cc.noharry:agent-app-protocol` and marked experimental: what this guide describes
> can change in a minor release, and their entry points (`ServerClient`, `AppServer`, `AppServerConfig`,
> `AppServerPlugin`) ask for an opt-in, `@ExperimentalAppServerApi`. Plugins are yours to write (see [Plugins](#plugins));
> one is published, `cc.noharry:agent-app-models`, the model services ([Model services](#model-services)).

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
`agent-app-sample-timer` (a kitchen timer with no screen and no model service, tested offline, whose test installs a
plugin package with a tool written as a script) and
`agent-app-sample-habits` (a habit tracker with no screen that answers approvals in code and switches a plugin of its
own off and on in its test). A test keeps the quotes in step with them.

## Add it to your app

Depend on `cc.noharry:agent-app-server` and `cc.noharry:agent-app-protocol`, on Maven Central from 0.5.0 and in
`agent-bom` with the runtime's other modules, and on `cc.noharry:agent-app-models` for what is known of the common
model services ([Model services](#model-services)). Before a version is released, build it from this repository's source:
check the repository out beside your project, add `includeBuild("../android-agent-runtime")` to your
`settings.gradle.kts`, and depend on the same coordinates at `VERSION_NAME` of this repository's `gradle.properties`;
Gradle builds them, and the runtime modules they need, from the source. The samples, which build in this repository,
name the same modules as projects.

The repositories, in `settings.gradle.kts` (this repository's own):

<!-- quote: settings.gradle.kts repositories -->
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

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
    // And what a plugin goes further with (HabitListPlugin's line for each run's context) asks for this one.
    compilerOptions.optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")
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
  option opts the whole module in; without it, each use is a warning. `AppServerPluginApi` is a second one, for what a
  plugin goes further with ([Plugins](#plugins)); without it, each use is an error. A module that only hosts the server
  needs the first alone.
- Core library desugaring with `desugar_jdk_libs_nio` 2.1.5 or later: the server's workspace and event log use
  `java.nio.file`, which Android has from API 26.
- `project(":agent-app-server")` and `project(":agent-app-protocol")` are the modules above; `agent-app-server` gives
  you the runtime's `ToolProvider`, `RuntimeHook` and `ModelClient` as well, and `agent-app-protocol` is the protocol
  and its client, plain Kotlin. Clients run on coroutines (`kotlinx-coroutines-android`). An app outside this
  repository names them by their coordinates, their version from `agent-bom` (CI builds the samples so too, against
  the artifacts `publishToMavenLocal` writes):

<!-- published: samples/agent-app-sample-habits/build.gradle.kts dependencies -->
```kotlin
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(platform("cc.noharry:agent-bom:0.5.0"))
    implementation("cc.noharry:agent-app-server")
    implementation("cc.noharry:agent-app-protocol")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
```

- For tests run offline under Robolectric ([Testing offline](#testing-offline-with-a-scripted-model)):
  `junit`, `robolectric` and `androidx.test:core`, and `unitTests.isIncludeAndroidResources` because the server reads
  its own assets.
- **Scripts.** Plugin packages' tools written as scripts, and the `scripts` built-in (`exec_js`), run in a JavaScript
  sandbox, `agent-exec-js` (QuickJS through Zipline). The server is compiled against it but does not bring it, as its
  native code is about 4 MB of an APK (QuickJS for four ABIs: the bare sample's release APK, shrunk with R8, is 1.5 MB
  without it and 5.6 MB with it). An app that wants scripts adds it, as the timer does (in its unit tests, too:
  [Script tools in a test](#script-tools-in-a-test)):

<!-- published: samples/agent-app-sample-timer/build.gradle.kts scripts-dependency -->
```kotlin
// Plugin packages' tools written as scripts run in this sandbox (QuickJS through Zipline); without it the server
// refuses such a package.
implementation("cc.noharry:agent-exec-js")
```

  Without it, `plugins/install` refuses a package with tools written as scripts (`SCRIPTS_UNAVAILABLE`), a call of
  such a tool from a package installed before fails with `scripts need agent-exec-js, which this app does not
  include`, and `AppServer.install` throws for `BuiltInFeatures(scripts = true)`. Its skills, rules and MCP servers
  need nothing more.

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

### Release builds and R8

A release build shrinks with R8 and needs no rules of the app's own for the server. kotlinx.serialization (the
protocol's types), Zipline, OkHttp and coroutines bring their rules in their artifacts, and so do the runtime's
modules: `agent-app-server` lets R8 build an app that leaves `agent-exec-js` out, and `agent-exec-js` keeps its
engine's class name, which the server looks up to know whether scripts can run. Beyond that, the server finds by name
only the plugins a library declares in its manifest ([Plugins a library brings](#plugins-a-library-brings)), and its
rules keep every plugin with its name. CI shrinks the bare sample's release with R8 (`minifyReleaseWithR8`) on every
check, and the notes sample's, which depends on `agent-app-models`, checking that the plugin its manifest declares is
in the release under its name (`checkReleaseDiscoveredPlugins`):

<!-- quote: samples/agent-app-sample/build.gradle.kts release -->
```kotlin
buildTypes {
    // R8 with no rules of the app's own: what the server and its libraries need comes in their artifacts. CI runs it.
    release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
}
```

That checks the build; what runs on a device is your release's own test, as for any app. Your own classes that you
reach by name, or that a library of yours reads by reflection, need rules of yours as ever.

## Where things live

Every name this guide uses that is not a sample's own, by package. `agent-app-server`'s public API is listed in
`agent-app-server/api/agent-app-server-levels.txt`, each line with its level: `host` (what this guide names, and the
interfaces a host may implement), `plugin` (`@AppServerPluginApi`, for plugin modules, which opt in) or `test`
(`@InternalAppServerApi`, for the server's own tests in other modules; never for an app's code). Every module's binary
API is in its `api/<module>.api`, as for the rest of the runtime. The protocol's methods are listed in
`AppServerMethods`, and each constant (`ClientRequests.TURN_START`, say) is a `RequestMethod` that names its wire method
and its parameter and result types (`Methods.kt` and one file per area, in `cc.noharry.agent.appserver.protocol`).

Those listings are files of this repository, not of the artifacts; an app that takes the artifacts from Maven reads
them on GitHub:
[`agent-app-server-levels.txt`](https://github.com/NoHarry/android-agent-runtime/blob/main/agent-app-server/api/agent-app-server-levels.txt),
[`agent-app-server.api`](https://github.com/NoHarry/android-agent-runtime/blob/main/agent-app-server/api/agent-app-server.api)
and [`agent-app-protocol.api`](https://github.com/NoHarry/android-agent-runtime/blob/main/agent-app-protocol/api/agent-app-protocol.api)
(on `main`; the tag `v<version>` has a release's). Where this guide says a declaration's KDoc is the reference, the
KDoc is in the sources and javadoc jars published beside each artifact, which an IDE shows from them.

| Package | Module | Names |
|---|---|---|
| `cc.noharry.agent.appserver` | `agent-app-server` | `AppServerPluginApi` |
| `cc.noharry.agent.appserver.server` | `agent-app-server` | `AppServer`, `AppServerConfig`, `AppServerPlugin`, `PluginContext`, `PluginDiscovery`, `PluginSkill`, `PluginMcpServer`, `PluginToolServer`, `PublishedState`, `RouteModule` |
| `cc.noharry.agent.appserver.runtime` | `agent-app-server` | `Persona`, `BuiltInFeatures`, `RunAddOns`, `RunFacts`, `HostModels`, `VoicePort`, `ProviderPresets`, `ReplayCheck` |
| `cc.noharry.agent.appserver.model` | `agent-app-server` | `ModelPreset`, `ScriptedModel`, `ProviderAdapter`, `ModelService`, `ServiceEndpoint`, `ServiceModel`, `ErrorRule`, `ModelFailure`, `ModelFailureKind`, `modelFacts`, `modelPrice` |
| `cc.noharry.agent.appserver.models` | `agent-app-models` | `ModelsPlugin` |
| `cc.noharry.agent.appserver.skills` | `agent-app-server` | `RuntimeSkill` |
| `cc.noharry.agent.appserver.protocol` | `agent-app-protocol` | `ExperimentalAppServerApi`, `ClientInfo`, `ClientCapabilities`, `ClientRequests`, `SettingsRequests`, `PluginRequests`, `ServerNotifications`, `TurnStartParams`, `TextParams`, `TurnInterruptParams`, `ThreadIdParams`, `ThreadStartResult`, `ThreadListResult`, `ThreadSnapshot`, `SessionSummary`, `Empty`, `ApprovalRequest`, `ApprovalDecision`, `ApprovalRespondParams`, `ConfirmationMode`, `ConfirmationStatus`, `RunSettingsParams`, `ProviderCreateParams`, `ProviderCreateResult`, `ModelSelectParams`, `ModelProvider`, `ProviderEndpoint`, `PluginSetParams`, `PluginListResult`, `PluginView`, `PluginInstallParams`, `PluginInstallResult`, `PluginConfirmParams`, `PluginRemoveParams`, `PackageView`, `PackageRefusal`, `PackageProblem`, `ConversationItem` (with `TurnHeader`, `MessageItem`, `ToolCallItem`, `ConfirmationItem`, `RuntimePhaseItem`), `TranscriptRole`, `ToolCallStatus`, `ModelFacts`, `ModelPrice`, `PriceBook`, `ServiceRequests`, `ServiceView` |
| `cc.noharry.agent.appserver.protocol.client` | `agent-app-protocol` | `ServerClient`, `ServerState` |
| `cc.noharry.agent.appserver.protocol.jsonrpc` | `agent-app-protocol` | `JsonRpcNotification`, `JsonRpcException`, `ProtocolJson` |
| `cc.noharry.agent.spi` | the runtime's `agent-api`, which `agent-app-server` brings | `ToolProvider`, `ToolDescriptor`, `ToolCallResult`, `ToolCallContext`, `RuntimeHook`, `HookDecision`, `HookContext`, `ToolUse` |
| `cc.noharry.agent.spi.model` | the runtime's `agent-api` | `ModelClient`, `ModelRequest`, `ModelEvent`, `ModelMessage` |

Names not in the table are Android's, Kotlin's and `kotlinx.coroutines`' (`MainScope`, `CoroutineStart`, `async`,
`first`, `withTimeoutOrNull`, `Mutex`), or the samples' own, each in its sample's package: `NotesTools`,
`AskBeforeSavingSecrets`, `NoteStatsPlugin`, `ModelChoice`, `transcript()` and `lastAnswer()` in the notes app;
`TimerAgent`, `TimerTools`, `TimerScript`, `timerConfig()` and `KITCHEN_PUBLISHER` in the timer; `HabitsAgent`,
`HabitsTools`, `HabitStore`, `RESET_TOOL`, `HabitListPlugin`, `HabitsScript` and `habitsConfig()` in the habits app;
`ExampleAdapter` and `ExamplePlugin` in `agent-app-models`' tests.

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
  are:
  - `TurnHeader`: the person's words (`fullText`).
  - `MessageItem`: `role` `ASSISTANT`, with `final` on the answer; `THINKING`, `STATUS`, `RUNTIME`, `NOTICE`, `ERROR`
    (what made a turn fail).
  - `ToolCallItem`, one per call the model made, the server's own tools' included: a turn that searched, called a
    tool and answered has `search_tools`, `mcp__notes__add_note` and `finish`. `toolName` is the full name, also for
    a call made through `call_tool`; `argumentsJson` and `resultJson` are what the model sent and read; `status` is
    `RUNNING`, `DONE`, `FAILED` (the tool failed, threw or was refused; `resultJson` says why), or `UNKNOWN` for one
    still running when its run ended.
  - `ConfirmationItem`: an approval (`pendingId`, `mode`, `alwaysAsk`) and its `status`, a `ConfirmationStatus`:
    `PENDING` while it waits, `APPROVED` or `DENIED` once answered, `EXPIRED` when nobody answered within a minute,
    `FAILED` when it can no longer be answered (its run ended or was stopped). `CONSUMED`, `AUTO_APPROVED` and
    `AUTO_DENIED` are in the protocol; this server does not set them.
  - `RuntimePhaseItem`: a phase of the runtime (intake, result, evidence), for a debug screen.
- `notifications` is every notification as it came (`ServerNotifications.TURN_COMPLETED` tells a turn ended).
- `request(method, params)` calls a method and returns its result, or throws a `JsonRpcException` (`code`, `message`,
  `data`) when the server refuses it. The ones a simple client needs:

| Method | Params | Result | What it does |
|---|---|---|---|
| `ClientRequests.TURN_START` | `TurnStartParams(text)` | `Empty` | Starts a turn in the current thread; `turn/completed` tells its end |
| `ClientRequests.TURN_STEER` | `TextParams(text)` | `Empty` | Adds the person's words to the running turn |
| `ClientRequests.TURN_INTERRUPT` | `TurnInterruptParams()` | `Empty` | Stops the current thread's run |
| `ClientRequests.THREAD_START` | `Empty` | `ThreadStartResult(threadId)` | A new conversation, which becomes the current thread |
| `ClientRequests.THREAD_LIST` | `Empty` | `ThreadListResult(threads, activeThreadId)`, each a `SessionSummary` (`sessionId`, `title`, `updatedAtEpochMillis`, `turnCount`, …) | The conversations |
| `ClientRequests.THREAD_SELECT` | `ThreadIdParams(threadId)` | `ThreadSnapshot(threadId, items)` | Makes another conversation the current thread |
| `ClientRequests.THREAD_DELETE` | `ThreadIdParams(threadId)` | `Empty` | Deletes a conversation |
| `ClientRequests.APPROVAL_RESPOND` | `ApprovalRespondParams(pendingId, decision)` | `Empty` | Answers an approval from its `ConfirmationItem` (a client without an `approvals` handler) |
| `SettingsRequests.RUN_SET` | `RunSettingsParams(confirmationMode, simulatedBackend, toggleStream)`: a field left null stays as it is | `Empty` | The approval mode, the backend, streaming |
| `SettingsRequests.PROVIDER_CREATE` | `ProviderCreateParams(label, provider, endpoint, apiKey, model, baseUrl)` | `ProviderCreateResult(profileId)` | Adds a model profile |
| `SettingsRequests.MODEL_SELECT` | `ModelSelectParams(profileId = …)` | `Empty` | Picks a profile |
| `PluginRequests.LIST` | `Empty` | `PluginListResult(plugins)`, each a `PluginView(id, enabled, running, required, needs, source, pkg)` | The plugins: on or off (`enabled`, the person's choice), running or not |
| `PluginRequests.SET` | `PluginSetParams(id, enabled)` | `PluginListResult` | Turns a plugin off or on |
| `PluginRequests.INSTALL` | `PluginInstallParams(url, file, data)`: one of the three | `PluginInstallResult(installId, pkg, needsAcknowledgement, update, expiresInSeconds)` | Fetches and checks a plugin package; nothing installed yet ([Plugin packages from a client](#plugin-packages-from-a-client)) |
| `PluginRequests.CONFIRM` | `PluginConfirmParams(installId, secrets, acknowledgeUntrusted)` | `PluginListResult` | Installs what `INSTALL` checked, and starts it |
| `PluginRequests.REMOVE` | `PluginRemoveParams(id)` | `PluginListResult` | Removes an installed package, its files and secrets |

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
  (`toolName`, its full name, and `argumentsJson`, the call's arguments as JSON: a `String?`, null, with `toolName`
  empty, when the server does not find the call among the thread's items), the approval mode (`mode`), the
  conversation (`threadId`), the site
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
  and `-` become `_`; a name over 64 characters is cut and ends in a hash). A plugin package's tools written as scripts
  go by the last segment of its id (`com.example.pantry.recipes`'s `cups_to_ml` is `mcp__recipes__cups_to_ml`), its
  MCP servers' by `<last segment>_<server id>`, and its skills are `<package id>:<skill>`
  ([Plugin packages from a client](#plugin-packages-from-a-client)).
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
 * "start a 5 minute egg timer", "list my timers", "cancel the egg timer"; and, with the kitchen measures package
 * installed, "how many ml in 2 cups".
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
    // A plugin package's script tools go by its id's last segment: com.example.kitchen.measures's are mcp__measures__….
    on(Regex("""(\d+(?:\.\d+)?)\s+cups?""")) { found ->
        call("measures", "cups_to_ml", mapOf("cups" to found.groupValues[1].toDouble()))
        answer { results -> sentence(results.single()) }
    }
    otherwise { answer("I'm Pip, the kitchen timer. Ask me to start, list or cancel a timer.") }
}
```

- `on(words)` matches words the person's message contains (ignoring case), `on(regex)` a pattern (the turn gets the
  match), `otherwise` everything else; the first that matches plays.
- `call(provider, tool, arguments)` takes the provider's `providerId` (for a plugin package's tools written as scripts,
  the last segment of its id: `call("measures", "cups_to_ml", …)` for `com.example.kitchen.measures`), the tool's name
  and its arguments as a map (or JSON text); calls run in order.
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
/**
 * Pip, a kitchen timer with no screen and no model service: its persona, its tools and rule, the script it plays, and
 * the publisher whose plugin packages it takes with no second yes.
 */
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
        // Its tools that say they only read run unasked; any other publisher's package needs the person's second yes.
        trustedPublishers = setOf(KITCHEN_PUBLISHER),
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
- **A restart** cannot be played in a test: the server is one per process (under Robolectric, one per SDK level), and
  nothing stops it and starts it again over the same files; a class at another SDK level gets a new server with files
  of its own. What a test can check of a switch is what `plugins/list` answers: each `PluginView`'s `enabled` (the
  person's choice, which the server keeps on the device) and `running`. That the choice outlives the process is the
  server's own test's (`PluginHostTest`). `AppServerTesting` is for the server's own tests in other modules, not an
  app's, and does not restart it either.
- **What the model is offered** can be checked too ([below](#seeing-what-your-model-was-offered)).

### Script tools in a test

A plugin package's tools written as scripts run in QuickJS in a test too. Under the JVM that needs two things, both in
the timer's build file, beside the sandbox itself ([Add it to your app](#add-it-to-your-app)):

<!-- quote: samples/agent-app-sample-timer/build.gradle.kts script-tests -->
```kotlin
// A unit test runs the scripts in QuickJS too, under the JVM: Zipline's JVM build carries QuickJS for macOS and
// Linux x86-64, its Android build only for devices. The same version agent-exec-js uses.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("app.cash.zipline:zipline")).using(module("app.cash.zipline:zipline-jvm:1.16.0"))
    }
}
// QuickJS reads numbers by the C library's numeric locale, which the JVM takes from the environment: under one that
// writes 0,5 it runs no script. LC_ALL would win over LC_NUMERIC, so it gives way (its character set kept).
val allLocale = providers.environmentVariable("LC_ALL")
tasks.withType<Test>().configureEach {
    environment("LC_NUMERIC", "C")
    allLocale.orNull?.takeIf { it.isNotEmpty() }?.let {
        environment("LC_CTYPE", it)
        environment("LC_ALL", "")
    }
}
```

- **QuickJS for the JVM.** `agent-exec-js` brings Zipline's Android build, whose QuickJS is built for devices only:
  under the JVM a script tool's call fails with `the script engine cannot run here: The JavaScript engine (QuickJS)
  did not load: UnsatisfiedLinkError: no quickjs in java.library.path`. The substitution puts Zipline's JVM build,
  `app.cash.zipline:zipline-jvm`, in its place in the unit tests only, at the version `agent-exec-js` depends on (1.16.0
  for this release; its POM names it). The JVM build carries QuickJS for macOS (arm64 and x86-64) and Linux x86-64, not
  for Linux on ARM or Windows. `testImplementation("app.cash.zipline:zipline-jvm:1.16.0")` also works while the JVM
  build comes first on the test classpath, but leaves both builds, which have the same classes, on it.
- **Numbers.** QuickJS reads numbers (a literal, `parseFloat`, `JSON.parse`) with the C library's `strtod`, which
  follows the process's numeric locale, and the JVM takes its locale from the environment: under one that writes 0,5
  (`LC_NUMERIC=de_DE.UTF-8`, `hy_AM.UTF-8`, or such an `LC_ALL`), `236.588` would be read as `236`. `agent-exec-js`
  checks once per process and runs no script where numbers are misread: each call fails with `the script engine
  cannot run here: JavaScript does not run in this process: its C library reads 0.5 as 0 under the process's numeric
  locale, … Start the JVM with LC_NUMERIC=C (and no LC_ALL).` The test task sets `LC_NUMERIC=C`, and `LC_ALL`, which would win over it, gives way. On Android
  nothing is needed: its C library reads numbers the same in every locale.

The timer's test installs a package through the protocol, as a client does
([Plugin packages from a client](#plugin-packages-from-a-client)), and asks for its tool:

<!-- quote: samples/agent-app-sample-timer/src/test/java/cc/noharry/agent/appserver/sample/timer/TimerAgentTest.kt install-test -->
```kotlin
val bytes = checkNotNull(javaClass.getResourceAsStream("/com.example.kitchen.measures-1.0.0.agentplugin")).use { it.readBytes() }
val source = PluginInstallParams(data = Base64.getEncoder().encodeToString(bytes))
assertEquals("installed com.example.kitchen.measures 1.0.0", onMain { TimerAgent.install(app, source) })
// Its script runs in QuickJS: 2 × 236.588 ml, read right under the numeric locale the build file gives the test.
assertEquals("That is 473 ml.", ask("how many ml in 2 cups"))
```

The package is the sample's `package/` directory (a `plugin.json` and `scripts/cups_to_ml.js`), built and signed with
`tools/agent-plugin` into `src/test/resources/`; `timerConfig()` trusts its publisher's key, so its tool that only
reads runs unasked. An installed package stays for the process's server, so the test removes it when done.

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
| `scripts` | `exec_js` (JavaScript in a sandbox), still off on the basic tools page until a client switches it on; needs `agent-exec-js` in the app ([Add it to your app](#add-it-to-your-app)) |
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

## Model services

A profile is a base URL, a model, a key and an endpoint kind: Chat Completions or Responses on an OpenAI-compatible
service, or the Anthropic Messages API. The server speaks those three wire protocols itself, and that is all
`agent-app-server` knows of models.

**With `agent-app-server` alone**, a profile reaches any compatible service (a `ModelPreset`, a profiles file, or one
the person adds), and nothing is known of any service or model:

- a model's context window is 128K tokens, and it is sent pictures, until the person sets otherwise for it;
- a profile has no base URL until one is given;
- its model list is the wire protocol's own: `GET <base URL>/models`, with a bearer key, or with `x-api-key` and
  `anthropic-version` on the Messages API;
- prices come from models.dev when a client asks for a sync (`usage/syncPrices`), or the person types them;
- a failed call carries the stable code the wire protocols give it: HTTP 401 and 403 `PROVIDER_AUTH_FAILED`, 402 and
  `insufficient_quota` `PROVIDER_QUOTA_EXCEEDED`, `context_length_exceeded` `PROVIDER_CONTEXT_WINDOW_EXCEEDED`, 429 and
  `rate_limit_error` `PROVIDER_RATE_LIMITED`, other 5xx and `overloaded_error` `PROVIDER_SERVER_ERROR`, 408, 504 and a
  call the HTTP client timed out `PROVIDER_TIMEOUT`; quota, context window and auth failures are not tried again,
  except that on a host no adapter knows (a relay) a 429 is tried again as the runtime decides, whatever code it gets,
  since a relay's `insufficient_quota` may be a per-minute limit (DashScope's) and not an empty balance. A streamed call
  whose model sends nothing in its first 20 seconds is not a timeout of the call: it is asked again as a transport
  failure (`PROVIDER_TRANSPORT_FAILED`; four minutes for the host's own on-device model). An error that comes inside an
  answer of HTTP 200 (MiniMax's `base_resp`, OpenRouter's failure after the model has started) is not read as one of
  these codes.

`CoreWithoutServicesTest` runs a turn this way against a service on the test's own machine.

**With `agent-app-models`**, the server knows the common services. Add the dependency; its plugin (`models`) is loaded
without your listing it ([Plugins a library brings](#plugins-a-library-brings)):

<!-- published: samples/agent-app-sample-notes/build.gradle.kts models-dependency -->
```kotlin
// The model services (DeepSeek, OpenAI, Anthropic and more): loaded by default, named nowhere in the app's code.
implementation("cc.noharry:agent-app-models")
```

It brings an adapter per service (`ProviderAdapter`), and for a profile on a service's host the server then has:

- **Connection defaults**: each service's endpoints, the first its default, its default model (the general model that
  calls tools on its default endpoint, the cheaper tier where the service's docs make one; the data file says why for
  each) and where a person gets a key (`services/list` gives them to a screen that offers the services: `keyHint`, a
  sentence in English, and `keyPages`, the page where a key is made per region, a blank region for every one, for a
  screen that words its own sentence; Moonshot, Zhipu, Model Studio, MiniMax and SiliconFlow have a site per region,
  whose keys work only there). A profile whose id is a service's id and that has no base URL takes the base URL of the service's endpoint of its kind when the
  host gave one, else of the service's default endpoint for its wire, and, on that default endpoint and without a
  model, the default model. What the host or the person gave is never replaced.
- **The model list** from the service's own list, without a prefix its ids carry; for a service that documents none,
  the models in the data, given without asking the service (`ServiceView.listsModels` false), so there a list fetched
  proves nothing of a key: a screen that checks one sends a request of the service's own.
- **Facts per model** (`ModelFacts`): context window, output limit (the documented ceiling of one answer), whether it
  reads pictures, calls tools and reasons, its reasoning efforts, and the output its requests ask for
  (`requestOutputTokens`), set only where the service's own default would cut answers short: Volcengine Ark answers
  stop at 4,096 tokens unless the request sets `max_tokens`, so its requests ask for 32,768. The server uses the window
  (the usage shown, compaction at 80 percent), the picture switch's default and the requested output (sent as the
  request's output limit; without one the wire client's default goes: the Messages API's 16,000, or none on the
  OpenAI wire); all of them are in `modelSettings/read`, for a screen to show, where the reasoning efforts are only
  those the runtime's variant menu can send for the model (a service's `xhigh` is left out where the menu has none). A
  model on a host no adapter knows (a relay's, its name perhaps behind a prefix such as `relay/`) gets the facts of the
  service that lists it.
- **Prices**, per million tokens, in their list's currency (`USD` or `CNY`, with `asOf`): `usage/read` prices every
  model a service lists, before models.dev. Moonshot, Zhipu, Alibaba Cloud Model Studio, MiniMax and SiliconFlow have a
  China list in yuan and an international one in dollars: a model is priced from the list of the region of the
  endpoint its profile ran at (`ModelPrice.region`), a profile at a host whose region the data has no list for (xAI's US
  endpoint, a Model Studio workspace outside Beijing and Singapore) has no service price, and a model named on a host no
  adapter knows takes the list of the service's first region. A price a service announced for a later day
  (`effectiveFrom`: Gemini 4's on 2026-12-01, Gemini 3.8 Flash's on 2027-01-01) applies from that day. DeepSeek's are its
  peak rates (off-peak costs half), and prices that rise above a prompt length (OpenAI over 272K, xAI from 200K, Qwen
  3.7 Plus over 256K, MiniMax M3 over 512K) are the lower ones: the tiers are not modelled. `PriceBook.costUsd(model,
  usage)` converts yuan at the book's rate. The book keeps only the prices typed in and those a sync found at
  models.dev; the host's and the services' are taken each time it is read, so a change to them applies at once.
- **Failures** read as each service's error pages say (a balance run dry, a context window exceeded, an overloaded
  service), before the wire protocols' reading.
- **DeepSeek's extras**: its search and page fetch APIs for `web_search` and pages that refuse the device, its own
  history updates on its Anthropic-compatible endpoint, its balance (`services/balance`), the check of a relay that may
  have DeepSeek behind it (`services/check`, `services/checked`), and one key for every profile on its host (`https`,
  `api.deepseek.com` exactly, its default port).

**Where a saved key goes.** A request about a connection (`services/balance`, `services/check`, `services/checked`,
the model list, saving the connection) takes a key typed in the request, or uses the one saved for its profile. A typed
key goes where the request says. A saved key goes only to the host (scheme, host and port) its saved profile points at,
or to an address that a check made with the key typed found and the server recorded for that profile and that key, and
only over https (plain http to this device or a private network address); for any other address nothing is sent and
the answer asks for the key again (`MODEL_KEY_TYPE_AGAIN`; `ServiceCheckView.savedKeyRefused` for a check). An adapter
reads the key only for the address it sends it to (`ConnectionKey.sendTo`), so it cannot send it elsewhere by
mistake.

The services, from the module's data file (`services.json`, each value with the official page it was read from and
the day; a test keeps this table equal to it):

<!-- services:start -->
| Service | `id` | Endpoints, the default first | Models in the data, with facts and prices; the default model in bold | Model list |
|---|---|---|---|---|
| DeepSeek | `deepseek` | chat_completions `https://api.deepseek.com`<br>messages `https://api.deepseek.com/anthropic` | **`deepseek-flash`**, `deepseek-v4-pro` (CNY) | `https://api.deepseek.com/models` |
| OpenAI | `openai` | responses `https://api.openai.com/v1`<br>chat_completions `https://api.openai.com/v1` | `gpt-6-astra`, **`gpt-6.1-sol`**, `gpt-6.1-terra`, `gpt-6-luna` (USD) | `<base URL>/models` |
| Anthropic | `anthropic` | messages `https://api.anthropic.com/v1` | `claude-opus-5-5`, **`claude-sonnet-5-5`**, `claude-haiku-5-5`, `claude-fable-5-2` (USD) | `<base URL>/models` |
| Google Gemini | `gemini` | chat_completions `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-4-carbon`, `gemini-4-argon`, **`gemini-3.8-flash`**, `gemini-3.5-flash-lite` (USD) | `<base URL>/models` |
| xAI | `xai` | chat_completions `https://api.x.ai/v1` (global)<br>chat_completions `https://us.api.x.ai/v1` (us)<br>responses `https://api.x.ai/v1` (global) | `grok-4.7`, **`grok-4.3`** (global USD) | `<base URL>/models` |
| OpenRouter | `openrouter` | chat_completions `https://openrouter.ai/api/v1` | none: its own list has them | `<base URL>/models` |
| Groq | `groq` | chat_completions `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b`, **`openai/gpt-oss-20b`** (USD) | `<base URL>/models` |
| Moonshot (Kimi) | `moonshot` | chat_completions `https://api.moonshot.cn/v1` (cn)<br>chat_completions `https://api.moonshot.ai/v1` (global) | **`kimi-k3.1-pro`**, `kimi-k3.1-pro-highspeed`, `kimi-k3` (cn CNY; global USD) | `<base URL>/models` |
| Zhipu (GLM) | `zhipu` | chat_completions `https://open.bigmodel.cn/api/paas/v4` (cn)<br>chat_completions `https://api.z.ai/api/paas/v4` (global) | `glm-5.3`, **`glm-5.3-flash`**, `glm-5.3-flashx` (cn CNY; global USD) | none documented: the models in the data |
| Alibaba Cloud Model Studio (Qwen) | `dashscope` | chat_completions `https://dashscope.aliyuncs.com/compatible-mode/v1` (cn)<br>chat_completions `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (global) | `qwen3.8-max`, `qwen3.7-plus`, **`qwen3.8-flash`** (cn CNY; global USD) | none documented: the models in the data |
| Volcengine Ark (Doubao) | `volcengine` | chat_completions `https://ark.cn-beijing.volces.com/api/v3` (cn) | `doubao-seed-2-1-pro-260915`, **`doubao-seed-2-1-lite-260915`**, `doubao-seed-2-1-turbo-260628`, `doubao-seed-evolving` (CNY) | `<base URL>/models` |
| MiniMax | `minimax` | chat_completions `https://api.minimax.cn/v1` (cn)<br>chat_completions `https://api.minimax.io/v1` (global) | **`MiniMax-M3.1-Flash`**, `MiniMax-M3` (cn CNY; global USD) | `<base URL>/models` |
| SiliconFlow | `siliconflow` | chat_completions `https://api.siliconflow.cn/v1` (cn)<br>chat_completions `https://api.siliconflow.com/v1` (global) | **`deepseek-ai/DeepSeek-V4-Flash`**, `zai-org/GLM-5.2`, `moonshotai/Kimi-K2.7-Code` (cn CNY; global USD) | `<base URL>/models` |
<!-- services:end -->

<!-- real-services:start -->
**Run against the services themselves:** DeepSeek and Alibaba Cloud Model Studio (DashScope) were exercised against the real services on 2026-11-11 (model list, a turn, a tool call, usage and cost); the others by mock tests only (`ServicesMockTest`).
<!-- real-services:end -->

`./gradlew :agent-app-models:realServiceTest` runs each service whose key is in `AGENT_E2E_<ID>_KEY` (the service's
id in capitals: `AGENT_E2E_DEEPSEEK_KEY`), optionally with `AGENT_E2E_<ID>_MODEL`, `_BASE_URL` and `_ENDPOINT`: it
lists the models, runs a plain turn and one that calls a tool, and checks their usage and cost. It is never part of the
build's checks, skips a service without a key, and prints no key.

What a service's pages leave out is not in the data, and the server keeps its default for it. Prices and models change
on the services' own schedule: the data is as of the day it names, and a change a service announced for a later day is
in the data with that day (`effectiveFrom`) and applies from it.

**Overriding facts and prices.** The host's own, by model id, win over a service's, fact by fact; the person's own
settings (a model's window, picture switch and requested output, a price typed in with its currency) win over both. A
host's price is in `USD` or `CNY`; another currency is a configuration error when the `AppServerConfig` is made:

<!-- quote: agent-app-models/src/test/java/cc/noharry/agent/appserver/models/OverridesTest.kt host-overrides -->
```kotlin
val config = AppServerConfig(
    name = "notes",
    // A gateway that serves this model with a smaller window and no pictures, at the price the host pays.
    modelFacts = mapOf("deepseek-flash" to ModelFacts(contextWindow = 500_000, imageInput = false)),
    modelPrices = mapOf("deepseek-flash" to ModelPrice(input = 0.2, output = 0.8, currency = "USD")),
)
```

`ignoredPlugins = setOf("models")` leaves the module's services out altogether, and a person turns them off and on as
any plugin (`plugins/set {"id":"models",...}`).

**A service of your own.** A company gateway, a relay or a service the module does not know needs no adapter: it is a
profile with its base URL. An adapter is for what the server should know of it, as the module's are, in a plugin
(`ctx.providerAdapter`, with the second opt-in, `@AppServerPluginApi`):

<!-- quote: agent-app-models/src/test/java/cc/noharry/agent/appserver/models/OwnAdapterExampleTest.kt own-adapter -->
```kotlin
/** A service the server should know: where it is, its model with its facts and price, how it says a key ran dry. */
object ExampleAdapter : ProviderAdapter {
    override val services = listOf(
        ModelService(
            id = "example",
            label = "Example AI",
            endpoints = listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.example.com/v1")),
            keyHint = "Create a key at https://example.com/keys",
            models = listOf(
                ServiceModel(
                    id = "example-chat",
                    facts = ModelFacts(contextWindow = 256_000, maxOutputTokens = 32_000, imageInput = true, toolCalls = true),
                    price = ModelPrice(input = 0.5, cacheRead = 0.05, output = 2.0, source = "example", asOf = "2026-11-11"),
                ),
            ),
            errors = listOf(ErrorRule(ModelFailureKind.QUOTA, status = 403, pattern = "credit_exhausted")),
        ),
    )
}

/** Its plugin, which a person can turn off and on like any other. */
object ExamplePlugin : AppServerPlugin {
    override val id = "example-service"

    override fun apply(ctx: PluginContext) = ctx.providerAdapter(ExampleAdapter)
}
```

A `ModelService` also says, as data, what the server's clients need to know of a service: where it lists its models
(`modelsUrl`, `listsModels`, `listedIdPrefix`), the usage fields it reports cached input in (`usageFields`), more hosts
its profiles may be on (`hosts`; `.example.net` takes every host ending with it) and how often a failed call is tried
again (`retry`). The adapter's members have defaults that read its services; it overrides one to do more: `facts` and
`price` (DeepSeek prices a version it does not list as its own model of that kind), `listModels` (a list fetched its own
way), `classify`, and the extras: `lookup`, `nativeUpdates`, `balance`, `check`, `checked`, `shareKeys`, `routes`.
`modelFacts(baseUrl, model)` and `modelPrice(model)` are what the server takes, the host's over the adapters'.

## Plugins

A plugin adds to the server from code of your own, and the person can turn it off and on while the app runs (the
choice is kept on the device). You write it against `agent-app-server` and list it in `AppServerConfig.plugins`; it may
be a class in your app or a module of its own, which then brings its manifest entries (a receiver, a service, a
permission) to the hosts that depend on it. One plugin is published with the server, the model services
(`agent-app-models`, [Model services](#model-services)), which its manifest brings to every app that depends on it
([below](#plugins-a-library-brings)). The other first-party ones (cards, operating the screen, the device's tools, drawn
pages, memory, pictures, plans, A2A) are source modules of the Bothy app, built into it and not Maven artifacts; their
source is published with Bothy's next release. Until then, the plugin below and the habits app's are the examples to
start from.

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

The habits app's plugin, which its test turns off and on, also gives each run a line of context:

<!-- quote: samples/agent-app-sample-habits/src/main/java/cc/noharry/agent/appserver/sample/habits/HabitListPlugin.kt plugin -->
```kotlin
/**
 * Listing every habit, as a plugin the person can turn off and on while the app runs
 * (`plugins/set {"id":"habit-list","enabled":false}`). Its tool is `mcp__habit_list__list_habits`; while the plugin is
 * off a conversation that found it before is told, when it calls it, that its server is off. Each run's context also
 * says how many habits are tracked, so a model knows whether there is anything to list before it calls.
 */
object HabitListPlugin : AppServerPlugin {
    override val id: String = "habit-list"

    override fun apply(ctx: PluginContext) {
        // Tools for each run, from the app's Context (and the run's other tool providers, unused here).
        ctx.tools { context, _ -> listOf(HabitList(context)) }
        // Lines for the system prompt, after the host's: the same every run, so the prompt stays cached.
        ctx.rules(listOf("When asked which habits are tracked, list them with the habit list tool."))
        // A line for each run's context, read anew every run (@AppServerPluginApi, its module's second opt-in).
        ctx.contextLines { facts -> listOf("Habits tracked: ${HabitStore(facts.context).names().size}.") }
    }
}
```

- **Where a context line lands.** Not in the system prompt. A run's context is a message right after the person's
  words: `From the app, not typed by the user: context for the request above.`, then its lines, one each: the time,
  the app in front, the host's own (`RunAddOns.contextLines`), then the running plugins' in the order they started. It
  is a `"user"` message (a `"system"` one for a model service that takes the context as an update, as DeepSeek's own
  endpoint does), made anew each run; the system prompt stays the same from run to run, so a service can cache it.
  `PluginServicesTest` pins where a plugin's line lands. A plugin's `rules` go into the system prompt, after the
  host's.
- **The second opt-in.** `contextLines` is marked `@AppServerPluginApi`, as are the other members a plugin goes
  further with: a module that uses one adds `compilerOptions.optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")`
  beside `ExperimentalAppServerApi`, as the habits build file does; without it, each use is a compile error.

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
needs; `required = true` keeps it always on. With the second opt-in it also takes, among others, `contextLines`
(above), `contextProviders` (what the model reads before each round of a run, marked as data, never instructions),
`runHooks` (gates on tool calls that read the run, `RunFacts`), `runTools` (tools that depend on the run, such as
whether its model reads pictures) and `wrapTools` (a wrapper around each tool provider of a run). Clients see plugins
with `plugins/list` and switch them with `plugins/set`; `serverInfo.features` follows (`server/features`).

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
- **Scripts** need the sandbox in your app ([Add it to your app](#add-it-to-your-app)): without `agent-exec-js`, a
  package with tools written as scripts is refused.

### Plugin packages from a client

A client installs a package in two steps, as a screen does: `plugins/install` fetches and checks it and says what it
is and asks for, with nothing installed; the person looks; `plugins/confirm` installs it and starts it. The timer
installs one with no screen, taking only what needs no second yes:

<!-- quote: samples/agent-app-sample-timer/src/main/java/cc/noharry/agent/appserver/sample/timer/TimerRequestReceiver.kt install -->
```kotlin
/**
 * Installs the plugin package [source] names (its bytes as base64, an HTTPS address or a file) in the protocol's
 * two steps: `plugins/install` fetches and checks it and says what it asks for, `plugins/confirm` installs it with
 * the secrets it asks for. A screen shows the person what it asks for in between; with no screen, Pip takes only a
 * package that needs no second yes (one from a publisher it trusts) and none that asks for a secret it was not
 * given. Its answer: what was installed, or why not.
 */
suspend fun install(app: Context, source: PluginInstallParams, secrets: Map<String, String> = emptyMap()): String = turns.withLock {
    val client = connected(app)
    try {
        val checked = client.request(PluginRequests.INSTALL, source)
        val pkg = checked.pkg
        // An unknown publisher or an unsigned package: confirming needs acknowledgeUntrusted = true, the person's yes.
        if (checked.needsAcknowledgement) return@withLock "not installed: ${pkg.publisher.name} is not a publisher Pip trusts"
        // An update keeps the secrets set before (set = true); a value given here replaces one.
        val missing = pkg.permissions.secrets.filter { !it.set && it.name !in secrets }
        if (missing.isNotEmpty()) return@withLock "not installed: it needs ${missing.joinToString { it.label }}"
        val plugins = client.request(PluginRequests.CONFIRM, PluginConfirmParams(checked.installId, secrets))
        val installed = plugins.plugins.single { it.id == pkg.id }
        "installed ${pkg.id} ${pkg.version}" + if (installed.running) "" else " (not running)"
    } catch (refused: JsonRpcException) {
        // A refused package or step: the error's data says what the server found.
        val why = refused.data?.let { ProtocolJson.decodeFromJsonElement(PackageRefusal.serializer(), it) }
        "not installed: " + (why?.let { "${it.problem} ${it.detail}".trim() } ?: refused.message)
    }
}

/** Removes the installed package [id], its files and secrets with it. */
suspend fun remove(app: Context, id: String) {
    turns.withLock { connected(app).request(PluginRequests.REMOVE, PluginRemoveParams(id)) }
}
```

- **`PluginRequests.INSTALL`**, `PluginInstallParams` with one of `data` (the package's bytes, base64), `url` (HTTPS)
  or `file` (a `content://` address, or a `file://` path in the app's external files, where `adb push` can put one).
  Its result, `PluginInstallResult`: `installId`, which confirms it within `expiresInSeconds` (a quarter of an hour);
  `pkg`, a `PackageView` for the screen (`id`, `name`, `version`, `description`, `publisher` with its `name`, key
  `fingerprint` and whether the host `trusted` it, `unsigned`, `permissions`: the `network` hosts and where each is,
  the `secrets` with a `label` each, the `hostTools`; `contents`: skills, MCP servers, lines of rules, and tools with
  whether each `asks`); `needsAcknowledgement`; and, for an update, `update`, what it asks for beyond the version
  installed now.
- **`needsAcknowledgement`** is true for a publisher the host does not trust (`AppServerConfig.trustedPublishers`) or
  an unsigned package (only where `allowUnsignedPackages` is on). The screen then shows a second, plainer warning, and
  the confirmation says the person accepted it: `acknowledgeUntrusted = true`. Without it, `plugins/confirm` is
  refused with `NEEDS_ACKNOWLEDGEMENT`.
- **`PluginRequests.CONFIRM`**, `PluginConfirmParams(installId, secrets, acknowledgeUntrusted)`: `secrets` are the
  values the person typed for `pkg.permissions.secrets`, by `name`. Every one is needed (`MISSING_SECRET`), except on
  an update, where one already `set` keeps its value unless given again. It answers the plugins after it
  (`PluginListResult`); the package is one of them, `source` `PACKAGE`, its `pkg` as above.
- **`PluginRequests.REMOVE`**, `PluginRemoveParams(id)`: stops the package and deletes its files and secrets. A
  plugin your build carries is not removed (`NOT_A_PACKAGE_PLUGIN`). A package is turned off and on as any plugin is,
  with `plugins/set`.
- **Refusals.** A refused package or step throws `JsonRpcException`, whose `data` is a `PackageRefusal(problem,
  detail)`: `ProtocolJson.decodeFromJsonElement(PackageRefusal.serializer(), data)`. `PackageProblem` lists every
  problem; their KDoc says what each means.
- **Names.** Its tools written as scripts go by the last segment of its id (`com.example.kitchen.measures`:
  `mcp__measures__cups_to_ml`), its MCP servers' tools by `<last segment>_<server id>`, its skills by `<id>:<skill>`.
  No two installed packages share one of those names, and a package does not take one of the names your other tool servers go by when it is
  installed (your `tools`, the MCP servers the person configured, your plugins'): `plugins/install` refuses it with
  `TOOL_SERVER_TAKEN`, naming the name (and the package that has it). A server the person adds later takes none of a
  package's names. If a tool provider of yours added in a later version of your app takes one, the package's tools
  go by that name with a digit (`measures2`).

### Plugins a library brings

A library can bring a plugin to every app that depends on it, without the app listing it: an entry in the library's
`AndroidManifest.xml` names the plugin's class (a Kotlin `object`, or a class with a public constructor that takes
nothing), and the manifest merger puts it in the app's. `agent-app-models` declares its plugin so:

```xml
<application>
    <meta-data
        android:name="cc.noharry.agent.appserver.models.ModelsPlugin"
        android:value="cc.noharry.agent.appserver.plugin" />
</application>
```

`AppServer.install` loads each plugin the app's manifest declares (`PluginDiscovery`), after the host's own. It is in
`plugins/list` and switched with `plugins/set` like any plugin. The host leaves one out by its id
(`AppServerConfig.ignoredPlugins`), and a plugin the host lists itself with the same id is loaded as listed, not
twice. One that cannot be made (a class that is missing or is not a plugin, which is not initialized; or one whose id
cannot be read) is left out, with a warning in the log. Unlike the host's own plugins, which fail `AppServer.install`
when they need a plugin it does not have or fail to start, a library's plugin that needs a plugin the app does not have
is left out, and one that fails to start stays off: `plugins/list` shows either with its `problem` (`NEEDS_MISSING`
with the ids it needs, `START_FAILED` with why), and the log has a warning. `agent-app-server`'s consumer rules keep
every class that implements `AppServerPlugin` with its name and the way it is made, so a release shrunk by R8 finds it
too; `checkReleaseDiscoveredPlugins` checks the notes sample's, and that its release carries the models plugin's data
file (`services.json`), without which that plugin does not start and says so.

## Going deeper (experimental)

For a host that needs more than plain values, `AppServerConfig` also takes: `presets`
(`ProviderPresets`: model services as a profiles file), `voice` (`VoicePort`: speech in and out), `models` (`HostModels`: models you run yourself, such as an on-device
one), `runs` (`RunAddOns`: what depends on each run: rules and gates that read `RunFacts`, context lines, loop settings,
tool wrappers, a spoken note on tool calls, subagent types), `replayCheck` (`ReplayCheck`: a bench's checks of finished
runs), `extraTrustRoots`, `scene`, `trustedPublishers`,
`allowUnsignedPackages` and `maxScriptToolSeconds`. Their KDoc is the reference.

A plugin that goes further uses more of the members of its `PluginContext` marked `@AppServerPluginApi` (with the
second opt-in, [Plugins](#plugins)). One that works the conversation
itself (a voice, a companion, a debug door) has `turns` (`Turns`: start, replace or stop a turn of the conversation on
screen, follow the runtime's state, start a new conversation, keep a turn something else answered, add a line of your
own), `speech` (`Speech`: words the person said, an acknowledgement said first, a preview of the voice, silencing the
run going on), `assistant` (`Assistant`: the client presenting the assistant on this device, its wake and card, and
requests and notifications of your own to it), `outsideTurns` (turns for a client outside the app, such as another
agent's task) and `request` (any protocol request, answered as a client's would be, by the server's routes and the
running plugins'). The others shape runs and the server, beside those under [Plugins](#plugins): `offer`/`offers`
(features named to clients), `providerAdapter` (a model service, [Model services](#model-services)), `skillSections`,
`prepareInput` and `turnLabel` (what each run gets), `onLocalTurn`, `onAppInFront`, `onPresenterReady` and
`onClearData` (what happens on the device), and `provide`/`service` (services plugins offer each other). Their KDoc is
the reference. A host keeps nothing of the server's own objects: what it
needs outside its plugins it reaches through one of its plugins' contexts.

## What is experimental, and what is not done

- All of it is experimental for 0.5.0. What this guide names before "Going deeper" is the part meant for every host;
  the rest is for hosts that go deep and is likelier to change.
- What `agent-app-models` knows of a service is its pages on the day its data names; a service's model list may hold
  models with no facts or prices in the data, which get the defaults. Of a model's facts, the requested output is sent;
  its output limit and reasoning efforts are shown, not sent (a variant the person picks sends what the runtime sends
  for every model of its wire). The runtime itself still names some services: its variant menu by model names, OpenAI's
  own API (`max_completion_tokens`) and DeepSeek's (its output limit by name, its updates and DSML).
- The protocol's methods are not yet marked stable or experimental one by one.
- Keys the person types (model profiles, MCP servers, plugin packages' secrets) are kept unencrypted in your app's
  private storage.
- Answering the model's `ask` from a client (`question/answer`) is not shown by the samples.
