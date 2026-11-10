# Moving the app server into this repository

Status: plan, 2026-11-09. Owner decisions so far: the app server ships in 0.5.0 marked experimental; the DeepSeek
adapter becomes a module of its own; the work goes in the order of section 3.

The app server (`agent-app-protocol`, `agent-app-server`) lives in the Bothy repository today
(`workspace/apps/android/`, branch `codex/api-redesign`). It is one agent runtime behind a JSON-RPC protocol of threads,
turns and items, in the style of Codex's app-server; every front end is a protocol client. This document says what has
to be true before it moves here and is published, and what a host's API looks like afterwards.

## 1. Where it stands

- `agent-app-protocol`: plain Kotlin, about 5,300 lines. `agent-app-server`: an Android library, about 27,000 lines,
  111 test files, 502 public declarations on about 170 public types.
- The generic modules import nothing of Bothy. A host gives everything of its own through `AppServerConfig`.
- Two dry runs (2026-11-09) built agents on the public API alone, without changing the generic modules:
  `agent-app-sample-notes` (a persona, a tool provider, a real model, one screen and a foreground service with no
  screen) and `agent-app-sample-timer` (no Activity, a scripted model; written by an agent that had only the sample,
  the API listing and the protocol sources). Both work. The notes app uses four of the server's types
  (`AppServer`, `AppServerConfig`, `Persona`, `RunAddOns`); everything else goes over the protocol.

What the dry runs found:

| Finding | Kind |
|---|---|
| A host's tools are not in the model's tool list: the model reaches them with `search_tools` then `call_tool`, as `mcp__<provider>__<tool>`, and ends with `finish`. Nothing public says so | docs |
| The sample shows only `AppServerConfig(name = …)`; the API listing is mostly unrelated to a host | docs, API size |
| Which tools ask for approval, and the values of `ToolDescriptor.effectKind`, are not written down | docs |
| The built-in simulated model only echoes; exercising tools needs a scripted model of the host's own | missing helper |
| `finish` with `outcome: "blocked"` ends the run as `REPLAY_FAILED` | behaviour to check |
| A host that turned nothing on still gives the model `write_file`, `edit_file`, `web_fetch`, `web_search` and the subagent tools | default |
| Something listens on 127.0.0.1:8766 in a host with `A2aHost.NONE` (seen on the notes app; `A2aHost.localServer` is false by default, so the listener is not yet identified) | default, to investigate |
| The merged manifest gains calendar and notification permissions (from `agent-device-capabilities`) and two debug receivers | default |
| Each run writes debug files that only grow (`runtime-debug-logs`, `debug-run-evidence`, `mcp-progressive-evidence`) | default |
| `Persona.GENERIC` is the default argument of `AppServerConfig` but is marked internal | API |

## 2. What the two references do

**Model adaptation.** Both keep it out of the core, in different ways.

- dsh (DeepSeek Harness, TypeScript): a provider is a plugin package. The agent loop has no provider name in it. A
  provider implements one abstract adapter whose only required method streams a completion; replay state is an opaque
  blob the core stores and never reads. DeepSeek is about six packages: one shared adapter library, two thin
  authentication plugins, and balance, hosted web search and request extras each behind a service of its own.
- Codex: a provider is a configuration record (base URL, key variable, headers); adding a compatible one is
  configuration only. What differs between models is data: a JSON catalog carries each model's capabilities, context
  window and prompt, and the core reads fields, never model names. Hosted web search, rate-limit parsing and login are
  separate crates.

**The embedder's surface.** Codex has no trait a host implements to add tools, models or behaviour. An embedder
starts the server, sends requests and reads events. Behaviour comes from configuration (providers, approval policy,
MCP servers, skills, hooks) and from the protocol (per-thread overrides, dynamic tools, approvals as server-to-client
requests). The protocol itself is large (108 stable request methods, 67 experimental) but it is a data contract:
generated from one table, checked in as schemas, compared in CI, and gated per method and per field by one
`experimentalApi` capability the client states at `initialize`. Its SDKs export about 35 names.

## 3. Order of work

1. **Clean defaults.** A host that turns nothing on gets: the tools the protocol needs (`search_tools`, `call_tool`,
   `finish`, `ask`, `update_todo`, reading the workspace) and nothing else; no listening port; no permission it did not
   ask for; no debug files. File writing, the web, subagents, JS, the A2A server and diagnostics are switches in
   `AppServerConfig`, off by default. Bothy turns on what it uses.
2. **The DeepSeek module.** `agent-provider-deepseek`: the adapter, balance, hosted lookup, probes and key sharing,
   through the existing `ProviderAdapter`. The generic module keeps no DeepSeek name; per-model facts it needs
   (context window, pricing, thinking levels) come from catalog data a host can replace.
3. **The host API in two tiers** (section 4), the rest internal.
4. **Docs and helpers.** One host guide (the model-side contract, approvals, the lifecycle), a scripted model for
   tests, the two dry-run apps as samples.
5. Repeat the no-context dry run; its obstacle list should come back empty.

Then the gates of the release: the modules build under this repository's `ciCheck`; Bothy drops their sources and
depends on the coordinates from a local Maven repository, compiled from clean; the samples build from the same
artifacts; the tablet run-through; Maven Central on the owner's word.

## 4. The host API

### Tier 1: what every host uses

Small enough to read in one sitting, and the only part the getting-started guide names.

| Type | Module | What it is for | Change from today |
|---|---|---|---|
| `AppServer` | server | `install(app, config)` when the process starts; `connect(context)` gives a client its channel | `features` and the rest become internal |
| `AppServerConfig` | server | Everything a host says about itself, as values | Gains the common cases as plain fields (below), so a simple host implements no interface |
| `Persona` | server | Name, the identity line of the system prompt, the answer language | `GENERIC` becomes public |
| `BuiltInFeatures` | server | The server's own features, each off by default | Gains `fileWriting`, `web`, `subagents`, `scripts`, `a2aServer`, `diagnostics`; has `plans`, `androidUse`, `deviceMedia`, `cards`, `startOnBoot` |
| `ModelPreset` | server | A model service the build carries: base URL, model, key, endpoint kind | New; today this needs three protocol calls or a `ProviderPresets` file |
| `RuntimeSkill` | server | A skill the host ships | As is |
| `ScriptedModel` | server (test support) | A model that plays a script of tool calls and replies | New |
| `ToolProvider`, `ToolDescriptor`, `ToolCallResult`, `ToolCallContext` | `agent-api` | The host's own tools | As is; `effectKind` values documented |
| `RuntimeHook` | `agent-api` | A gate before a tool runs | As is |
| `ServerClient`, `ServerState`, `ClientInfo`, `MessageChannel` | protocol | A front end's connection and the state it follows | As is |
| The stable protocol methods and items | protocol | `turn/start`, `turn/interrupt`, `thread/*`, `run/set`, `provider/*`, `model/select`, `ConversationItem`, approvals | Marked stable or experimental per method |

`AppServerConfig` for the common case, with no interface to implement:

```kotlin
AppServer.install(this, AppServerConfig(
    name = "notes",
    persona = Persona("Notes", "You are Notes, an assistant that keeps the user's notes on this device."),
    tools = { context -> listOf(NotesTools(context)) },
    rules = listOf("Read the notes before answering about them."),
    model = ModelPreset(baseUrl = "https://api.example.com", model = "example-chat", apiKey = BuildConfig.MODEL_KEY),
    approvals = ConfirmationMode.ALLOW_ALL_SESSION,
))
```

`tools`, `rules`, `skills`, `hooks`, `model` and `approvals` are the fields a first host needs; they replace
implementing `RunAddOns` for those cases.

### Tier 2: extension points for a host that goes deep

In a package of their own (`cc.noharry.agent.appserver.host`), each marked experimental, named in the reference
only. Bothy is the one host that uses them today.

| Type | What it is for |
|---|---|
| `RunAddOns` (what is left of it) | Loop settings, protocol rules, context lines, tool descriptions, the note field on tool calls, tool wrappers, subagent kinds, device servers |
| `HostModels` | Models the host runs itself (an on-device model), and its scripted model |
| `ProviderAdapter`, `ProviderProfile` | What a model service offers beyond chat: balance, lookups, native history updates |
| `VoicePort`, `RunAnnouncer` | Speech in and out |
| `A2aHost`, `A2aDelegationSink` and their report types | What the host does with other agents' tasks |
| `CardHooks` | Checks and closings for cards |
| `HostPlaces` | Where the host's screens show what tools leave |
| `AppServerFeature`, `RouteModule`, `RouteTable`, `PublishedState` | Protocol methods and state sections of the host's own |
| `RunFacts`, `SubagentKind`, `ToolNote`, `ToolNotes`, `AnswerProblems`, `FoundException` | What the interfaces above name |

Moved out or renamed on the way: the bench hooks (`requiresAction`, `answerProblems`) go to a replay-check interface
of their own; `exampleToolsAllowed` and `deviceServers` get names that say what they do; `asksDriver` loses the
driver.

### Internal

Everything else, about 120 types: `RuntimeSession`, `ServerBackend`, `SettingsBackend`, `ServerConnection`,
`ServerRoutes`, `Presenter`, the active-run registry, `evidence.*`, the MCP and A2A loaders and consoles, the model
helpers, session and debug constants, `UsageStore`, `UserMemoryStore`, `TrajectoryRepository`, the subagent settings
functions. Bothy's modules reach some of these today; each such use moves to the protocol or to a tier 2 type.
Broadcast receivers stay public classes, as the manifest needs, and out of the reference.

The `deepseek` package (16 declarations) leaves with its module.

### The protocol

Marked per method, as Codex does: a client that does not state `experimentalApi` at `initialize` is refused the
experimental methods and does not receive the experimental notifications. The stable part is exported as a listing and
compared in CI.

## 5. Plugins (owner's direction, 2026-11-09)

After looking at dsh the owner chose its principle without its framework: everything but the core is a plugin a host
can leave out, decided when the app is built; which of the plugins in a build are on is saved on the device and read
when the process starts. No plugin code is loaded at run time (MCP servers, skills and A2A peers are the extensions
that need no new build). This replaces tier 2 of section 4 as it was first written: instead of large host interfaces
(`RunAddOns` with 16 methods), one plugin interface, and the server's own features as first-party plugins, each a
published module.

`AppServerPlugin` (was `AppServerFeature`), every part but `id` optional:

| Member | What it adds |
|---|---|
| `id` | Its name among the host's plugins, unique in `AppServerConfig.plugins` |
| `install`, `appInFront`, `presenterReady` | What it does with the process and the app's screens |
| `routes`, `publish` | Requests and state sections of its own |
| `offers` | Server features it brings, named to clients in the handshake |
| `providerAdapters` | What it knows of model services beyond chat |
| `toolProviders(context, mounted)` | Tools for a run, which may call the run's backend tools |
| `wrapTools(provider)` | A wrapper around each backend tool provider |
| `prepareInput(sessionId, text, previous)` | The input a run gets in place of what the person sent; called for every turn |

What a plugin needs of the server beyond a host's API is marked `@AppServerPluginApi` (an opt-in of its own, set by
compiler option in a plugin's module); `@InternalAppServerApi` stays for tests only.

Two pilots, in the Bothy repository (`codex/api-redesign`, commits c88a7eb7 and 0b011f50), both passing its
validation and a run on the tablet:

| Plugin | Modules | Lines moved | What it needed opened |
|---|---|---|---|
| DeepSeek | `agent-app-deepseek`, `agent-app-deepseek-protocol` (its requests and views, for a front end that shows them) | about 800 | Two helpers made public (`httpFailure`, `ProviderConfig.keyFor`); four more for its tests |
| Cards and offers | `agent-app-cards` | about 1,170 | The four new plugin points above (`offers`, `toolProviders`, `wrapTools`, `prepareInput`); nine declarations marked `@AppServerPluginApi` (`modelText`, `PreviousExchange`, schema validation, the card tool server's name, answerable statuses, a generated picture's file, a web lookup) |

The app server and its protocol import neither plugin; Bothy's layering check enforces it. `BuiltInFeatures.cards`,
`RunAddOns.cards` and `AppServerConfig.providerAdapters` are gone: a host lists `CardsPlugin { hooks }` and
`DeepSeekPlugin`.

Left from the pilots:

- The cards module still opts in to `@InternalAppServerApi`, for its own test-only names and for `AppHost`
  (`AppHost.words`). `AppHost` needs a plugin-facing face.
- Card and offer views are still in `agent-app-protocol` (`ServerState.offers`, `WidgetCard`); a plugin's protocol
  half can move out as DeepSeek's did.
- The core still names the card tool server (`CARD_TOOL_SERVER`) in two lists of the server's own tool servers, and
  the subagent and page prompts ask whether cards are offered.
- DeepSeek facts remain in the core as code: pricing aliases (`usage/ModelUsage.kt`), a context window
  (`model/ContextWindowConfig.kt`), cache-token field names (`usage/TokenUsage.kt`). They should become catalog data.
- Saving which plugins are on, and reading it at start, is not built; today the host's list is the set.
- On the tablet `deepseek/balance` answered `MODEL_BALANCE_KEY_REJECTED` for the profile in use while chat on the same
  profile works. The moved code is unchanged, so this is taken to predate the move; not confirmed.

How the rest splits, by how many files outside each package use it: pages (3), screen operation (5), memory (6),
images (7) are next; plans (18) and A2A (21) use each other and stay in the core module for 0.5.0, registered through
the same interface inside it.

## 6. The plugin kernel and downloadable plugins (owner's direction, 2026-11-09)

The owner then chose to go further than section 5: build the kernel first so plugins load and unload while the server
runs, then let a person download plugins inside the app. Bothy is distributed by its own channel, not an app store.

**The kernel** is built (Bothy repository, `codex/api-redesign`, commit 7601b34c; `server/AppServerPlugin.kt`,
`server/PluginHost.kt`, `PluginHostTest`). It replaces the eight optional methods of section 5:

- A plugin is `id`, optional `needs` (ids of plugins it needs) and `required`, and one `apply(ctx)`.
- Everything it adds it says to its `PluginContext`: `routes`, `offer`, `providerAdapter`, `tools`, `wrapTools`,
  `prepareInput`, `publish`, `onAppInFront`, `onPresenterReady`, `provide`/`service`, a `scope` cancelled when it stops,
  and `onStop` for what the server does not know of. Stopping takes all of it back.
- `PluginHost` runs a plugin when it is on and what it needs runs, stops dependents first, and keeps the switches on
  the device (on by default). The rest of the server reads one snapshot, `AppHost.additions`, replaced whole on a change.
- Clients: `plugins/list`, `plugins/set`, and the notification `server/features` after a change.

Checked on the tablet: turning cards and DeepSeek off while Bothy ran removed `cards` from the features, the card tool
from the next turn and the `deepseek/*` requests at once; the choice survived a restart; turning them on brought all
of it back; a required plugin refused.

What the kernel does not yet cover:

- The core is not a plugin: 31 singletons hold state (`AppHost` 110 references in 44 files, `RuntimeSession` 94 in 19,
  the rest small). Each feature taken out of the core becomes switchable; the core's own features (plans, A2A, memory,
  images, pages, screen operation) are not yet.
- Bothy's voice, on-device model, Playground and settings plugins are marked `required`: their code is reached
  directly from elsewhere in Bothy, so they cannot stop yet.
- A plugin's state sections are cleared only where it says so in `onStop`.

**Downloadable plugins**, in this order after the kernel:

- A. Declarative packages: a manifest (name, version, signature, what it asks for), skills, rules, MCP server
  addresses, card and page templates. One generic plugin turns an installed package into `PluginContext` additions, so
  the core sees no difference from a built-in plugin.
- B. Sandboxed scripts in a package: tools written in JavaScript, run in the QuickJS sandbox the runtime already ships
  (`agent-exec-js`, through Zipline), reaching only the host interfaces the package asked for. Not yet tried as a
  plugin runtime.
- Native code is not loaded from a download. Features that need a system capability stay plugins built into the host.
- The catalog of plugins (index, review, updates) is a product of its own; the library offers installing a package
  from an address.

## 7. Decided by the owner on 2026-11-10, and the order of work to 0.5.0

Uploads to Maven Central are limited each month, in versions and in files, so 0.5.0 is released once, complete, with
as few published artifacts as serve it. That settles two things the earlier sections left open.

- **The whole core moves onto the plugin kernel before 0.5.0**, not across later versions: the server's own features
  (plans, A2A, memory, images, pages, screen operation, device tools) become plugins, and the singletons that hold
  state (`AppHost`, `RuntimeSession` and the rest, section 6) become services of the kernel. A later release only to
  finish the split would spend uploads on nothing a host asked for.
- **Plugins are modules in the source and few artifacts in a release.** A plugin stays its own Gradle module, so the
  layering check keeps the core from importing it; what is published is to be settled before the first plugin is
  uploaded, with one artifact for the first-party plugins of pure logic as the starting proposal (a host lists the
  ones it wants; its shrinker drops the rest), and separate artifacts only where a plugin brings a manifest entry, a
  permission or a native library a host must be able to leave out.

Also decided: the approved-call change of 6c40996 stays; an "always ask" answer for a hook is added to the runtime
before 0.5.0, and the ACP agent stops passing the server's asking rule by; old conversations are not kept readable
across the removal of the `TTS:` marker; secrets are encrypted before plugins are offered to people, which does not
hold 0.5.0; scripts calling the runtime's own tools, limits on how often a package's tools run, and compiled code in
packages come after 0.5.0.

The order: push what is local here after a review; clean defaults, the heavy optional modules behind plugins, and
what listens on 8766; `AppServerConfig` for a simple host, a scripted model and the host guide; the "always ask"
answer and ACP; the rest of the core onto the kernel; the three things seen in passing (an A2A task ending `failed`
with a good reply, `deepseek/balance` refusing a key that chat accepts, `finish` with `blocked` ending as
`REPLAY_FAILED`); the dry run of a third party, which is the gate; then the release, on the owner's word. After it:
Bothy pushed, a screen for plugins, encrypted secrets, a catalog.

## 8. Open questions

- What listens on 8766 in a bare host.
- Whether `agent-device-capabilities`, `agent-android-use` and `agent-exec-js` become `compileOnly` behind their
  `BuiltInFeatures` switches, so a host that leaves them off does not carry them.
- Whether tier 2 stays in `agent-app-server` or becomes an artifact of its own.
