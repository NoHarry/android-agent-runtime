# The app server

Status: experimental. The protocol and the server are two published artifacts, `agent-app-protocol` and
`agent-app-server`, in `agent-bom`; their entry points ask for an opt-in, `@ExperimentalAppServerApi`, and may change
in a minor release.

The app server makes an Android app an agent: one agent runtime behind a JSON-RPC protocol of threads, turns and
items, in the style of Codex's app-server. The app is its host: it installs the server when its process starts and says,
as plain values, who the assistant is, which tools it has and which model answers. Every front end (a screen, a
service, a broadcast receiver, a test) is a protocol client. This document says how it is put together; the guide for
hosts is `agent-app-server/README.md`, and plugins a person downloads are in `plugin-packages.md`.

## 1. What is where

- **Here.** `agent-app-protocol` (plain JVM: the protocol, the state a front end renders, and its client
  `ServerClient`), `agent-app-server` (Android: sessions, models, MCP, skills, subagents, usage, the plugin kernel and
  plugin packages), four apps on it (`samples/agent-app-sample`, `-notes`, `-timer`, `-habits`), `tools/agent-plugin`
  (makes plugin packages) and `tools/app-server-api` (lists the server's API by level). They pass `ciCheck` like the
  rest of the repository: source rules, API dumps, Android API level checks, lint, unit tests. CI also builds the
  samples against what `publishToMavenLocal` wrote (`-PsamplesFromMavenLocal`), so published metadata that leaves out
  what a host needs fails there.
- **The first-party plugins** (cards, DeepSeek's extras with their protocol half, operating the screen, the device's
  tools, drawn pages, memory, pictures, plans, A2A) are source modules of the Bothy app, built into it and not Maven
  artifacts; their source is published with Bothy's next release. Each is a Gradle module with its own tests and API
  dump, depends only on the published artifacts and on the other plugins, and names nothing of Bothy, so it can be
  built on its own.
- **No plugin is published.** A host writes its own (`AppServerPlugin`), or a person installs a package.

## 2. The API and its levels

- **Experimental.** `ServerClient`, `AppServer`, `AppServerConfig` and `AppServerPlugin` carry
  `@ExperimentalAppServerApi`; without the opt-in each use is a warning.
- **Three levels.** Every public declaration of `agent-app-server` is listed with its level in
  `agent-app-server/api/agent-app-server-levels.txt`, written and checked by `tools/app-server-api/api_dump.py` as part
  of `apiCheck`: `host` (what the guide names, and the interfaces a host may implement), `plugin`
  (`@AppServerPluginApi`, for plugin modules, which opt in by compiler option) and `test` (`@InternalAppServerApi`, for
  the server's own tests in other modules). Each module's binary API is in its `api/<module>.api`.
- **The protocol** is a data contract: each method is a `RequestMethod` or `NotificationMethod` constant naming its wire
  name and its parameter and result types, and `AppServerMethods.all` lists them, which the tests check against the
  methods the server answers. Methods are not yet marked stable or experimental one by one.

## 3. A host's API

What every host uses, small enough to read in one sitting:

| Type | Module | What it is for |
|---|---|---|
| `AppServer` | server | `install(app, config)` when the process starts; `connect(context)` gives a client its end of an in-process connection |
| `AppServerConfig` | server | Everything a host says about itself, as values |
| `Persona` | server | The assistant's name, the line its system prompt opens with, the language it answers in |
| `BuiltInFeatures` | server | The server's own features, each off until the host turns it on: `fileWriting`, `web`, `scripts`, `subagents` |
| `ModelPreset` | server | A model service the build carries: base URL, model, key, endpoint kind |
| `RuntimeSkill` | server | A skill the host ships |
| `ScriptedModel` | server | A model that plays a script of tool calls and replies, for tests offline |
| `ToolProvider`, `ToolDescriptor`, `ToolCallResult`, `ToolCallContext` | `agent-api` | The host's own tools |
| `RuntimeHook` | `agent-api` | A gate before a tool runs |
| `ServerClient`, `ServerState`, `ClientInfo` | protocol | A front end's connection and the state it follows |

The common case implements no interface:

```kotlin
AppServer.install(this, AppServerConfig(
    name = "notes",
    persona = Persona("Notes", "You are Notes, an assistant that keeps the user's notes on this device."),
    tools = { context -> listOf(NotesTools(context)) },
    rules = listOf("Read the notes before answering about them."),
    model = ModelPreset(baseUrl = "https://api.example.com/v1", model = "example-chat", apiKey = BuildConfig.MODEL_KEY),
    approvals = ConfirmationMode.ASK_EVERY_TIME,
))
```

A host that goes deeper also gives `presets` (`ProviderPresets`: model services as a profiles file), `voice`
(`VoicePort`), `models` (`HostModels`: models it runs itself), `runs` (`RunAddOns`: rules and gates that read each
run's `RunFacts`, context lines, loop settings, tool wrappers, subagent kinds), `replayCheck` (`ReplayCheck`: a bench's
checks of finished runs), `extraTrustRoots`, `scene`, and the package settings `trustedPublishers`,
`allowUnsignedPackages` and `maxScriptToolSeconds`. Everything else of the server is internal; a host reaches more only
through a plugin's context.

## 4. Defaults

A host that turns nothing on and lists no plugin gets:

- **The tools the protocol needs, and no more**: `search_tools`, `call_tool`, `finish`, `ask`, `ask_async`,
  `update_todo`, and reading the workspace (`list_dir`, `read_file`, `grep`). The host's own tools are found with
  `search_tools`, not placed in the tool list.
- **No socket.** With `debugSockets` the server listens on two abstract local sockets, the protocol's and the Agent
  Client Protocol's, which only the app itself, adb's shell and root may connect to. The server does not check that the
  build is debuggable: a host sets it in its debug builds, since the shell then has every request a client has.
- **No permission or component it did not ask for.** The server brings one receiver, `BootReceiver`, off until a
  running plugin asks to start after a reboot. Every build of `samples/agent-app-sample` checks its merged manifest.
- **No diagnostic files.** With `diagnostics` the server keeps, until runtime data is cleared, each model call's request
  and reply, each run's events and replay check, and the probes of the bench prompt.

What it keeps either way (conversations, their event logs, the model's workspace, settings, keys the person typed) is
in the app's private storage; the guide lists where.

## 5. Asking before a tool acts

- **One rule, whoever brought the tool.** A call of a tool that does not say it only reads waits for the person's yes,
  whether the tool is the host's, a configured MCP server's (which says so with `readOnlyHint`), a plugin's or the
  server's own (`write_file`, `edit_file`, `exec_js`). The approval mode answers it: "ask every time" asks each time,
  "allow confirmed domains" once per tool in a conversation, "allow all" runs it unasked, "deny all" refuses it. The
  host names tools that act but need no asking in `unaskedTools`; its hooks run first and may deny or ask on any call.
- **The mode** a person chose is kept across restarts; `AppServerConfig.approvals` is where a new installation starts.
- **Tools from outside the host** (a plugin's tools given with `askBeforeActions`, its MCP servers with the same flag,
  every plugin package's tools) ask every time in every mode but "deny all", which refuses them; a yes is never
  remembered, and a subagent's such call is refused. A package from a publisher the host does not trust is not taken
  at its word that a tool only reads: its script tools and every tool of its MCP servers ask.
- **Where nobody can be asked**: a subagent inherits its turn's mode and is refused what would ask; a turn a plugin
  delivers asks as the person's would, and an approval nobody answers expires after a minute.
- **The ACP socket.** Each prompt over it runs on a runtime built when it starts, as the app's own turns are, with the
  same gates. Its session modes are the approval policies; a call a gate marks to ask every time is a permission
  request in every mode, approve-all included, without the "always" option.

## 6. The plugin kernel

- **A plugin** is an `AppServerPlugin`: an `id`, the ids of the plugins it `needs`, whether it is `required` (always on),
  one `apply(ctx)`, and `clearWhileOff` for what it keeps for conversations while it is off. Everything it adds it says
  to its `PluginContext` (tools, rules, skills, MCP servers, tool servers, requests of its own, state, work of its own,
  and, at the `plugin` level, run gates, context, input preparation, services for other plugins, the conversation on
  screen and speech), and all of it is taken back when it stops.
- **`PluginHost`** runs a plugin when it is on and every plugin it needs runs, and stops dependents first. Which plugins
  are on is kept on the device (on by default). Plugins can be added, replaced and removed while the server runs (an
  installed package is one); one that fails to start stays registered and stopped. The rest of the server reads one
  snapshot of what the running plugins add, replaced whole on each change.
- **Turned off and on while the app runs**, a plugin's requests come and go at once; what runs get from it changes from
  the next turn. A conversation that found one of its tools keeps it while it is away and is answered
  `TOOL_UNAVAILABLE` on a call, and calls it again once it is back.
- **Clients** see plugins with `plugins/list`, switch them with `plugins/set`, and are told of a change by
  `server/features`.
- **What is not a plugin.** The core (sessions, models, MCP, skills, subagents, usage) is always there. Its state is
  held in process-wide objects (`AppHost`, `RuntimeSession` and others), so there is one server per process.

## 7. Layering

- `checkAppServerLayering` (part of `ciCheck`): the core names no plugin's package (the first-party plugins' packages are
  listed in the root build file).
- `checkCoreDependencies`: none of `agent-a2a`, `agent-a2a-client-official`, `agent-android-use` or
  `agent-device-capabilities` is on the core's classpaths, so a host that lists no plugin merges neither their code
  nor their manifests (an accessibility service, calendar and notification permissions). A plugin that needs one brings
  it in its own module.

## 8. What the plugins take from the published protocol

The plugins' own requests are declared in their modules where they can be (DeepSeek's in its protocol module), but some
plugins' protocol halves are in `agent-app-protocol`, shared with the front ends that show them, and `ServerState`
folds their sections. Besides the shared basics (`Empty`, `Finding`, `FindingCode`, `SaveResult`, `DoneResult`,
`FeatureRequests`, `SettingsRequests`, `ServerFeatures`, `PresentationRequests`, `PresentationNotifications`,
`ConfirmationMode`, `jsonrpc.*`):

| Plugin | In `agent-app-protocol` | `ServerState` |
|---|---|---|
| Cards | `view.WidgetCard`, `CardItem`, `CardItemKind`, `CardLayout`, `CardProgress`, `Offer`, `OfferOption`, `ChoiceOffer`, `ChoiceOption`, `SliderOffer`, `SwitchOffer`, `Control`, `BoundCall`, `ReplyWords` and their helpers | `offers` |
| DeepSeek | `BalanceView`, `BalanceAmountView`, `ModelProvider`, `ProviderEndpoint` | |
| Screen operation | `AndroidUseState`, `AndroidUseView`, `CapabilityRequests`, `PointParams`, `DragParams` | |
| Pictures | `ImageModelRequests`, `ImageModelConfig`, `ImageModelProblem`, `ImageModelSaveResult`, `ImageTryResult`, `ModelListResult`, `GeneratedImageResult`, `GeneratedPicture`, `view.IMAGE_GENERATE_TOOL`, `view.GENERATED_IMAGES_DIR` | `offerPictures` |
| Memory | `MemoryRequests`, `MemoryView`, `UserMemory`, `MemoryCategory`, `MemorySource`, `MemoryWriteResult`, the `MAX_MEMOR*` limits | `memory` |
| Pages | `PageRequests`, `PageReadResult`, `view.SHOW_HTML_TOOL`, `view.DRAWN_PAGE`, `view.DRAWN_PAGES_DIR` | |
| Plans | `Plan`, `PlanDraft`, `PlanAction`, `PlanTrigger`, `PlanEnd`, `PlanKind`, `PlanFiring`, `PlanWatch`, `WatchMode`, `WatchOp`, `PlanNoticeEntry`, `PlanRanParams`, `SubscriptionState`, `nextDueMillis`, `repeatAt`, `listing` | `plans`, `planHistory`, `planFeeds` |
| A2A | `A2aProfile`, `A2aModuleState`, `A2aTaskStatus`, `A2aOutcome`, `A2aInputSource`, `A2aClientState`, `A2aClientItem`, `DelegationView`, `DelegationStage`, `DelegationStep`, `DELEGATION_TURN_LABEL` | `delegations` |
| The device's tools | nothing beyond the basics | |

They are protocol types and ship with it; the two artifacts' public API names no type of a plugin module.

## 9. Known limits

- **Model facts as code.** Some of what differs between models is in the core as code rather than data a host can
  replace: DeepSeek's price aliases (`usage/ModelUsage.kt`), its context window (`model/ContextWindowConfig.kt`) and
  its cache-token field names (`usage/TokenUsage.kt`).
- **Protocol values of plugins a host may not list**, such as `FindingCode.MODEL_DEEPSEEK_AVAILABLE`, the
  `FindingCode.MEMORY_REFUSED_*` codes and `RuntimeDemoState` (`DemoSection`), are in the protocol whether or not the
  plugin runs.
- **Keys are not encrypted.** Model profiles' keys, MCP servers' keys and plugin packages' secrets are kept as plain
  text in the app's private storage (the latter two in its no-backup storage).
- **`agent-exec-js` is the host's to add.** The server is compiled against it (`compileOnly`) and finds it by its
  engine's class name, which its consumer rules keep; it does not carry it, as QuickJS is about 4 MB of an APK. Without
  it, `AppServer.install` refuses `BuiltInFeatures.scripts`, `plugins/install` a package with script tools, and a
  script tool of a package installed before fails its calls saying so.

## 10. In scope, and reserved for later

- **Built:** the plugin kernel, plugins a host builds in, and plugin packages a person installs (declarative ones and
  tools written as scripts in the sandbox), with the trust and permissions of `plugin-packages.md`.
- **Reserved:** compiled code in a package (DEX, for a publisher the host trusts only; the format refuses it today), a
  catalog of plugins (listing, review, updates: a product of its own; the library installs a package from an address,
  a file or its bytes), scripts calling the runtime's own tools, and limits on how often a package's tools run.
