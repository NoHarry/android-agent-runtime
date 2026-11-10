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

## 5. Open questions

- What listens on 8766 in a bare host.
- Whether `agent-device-capabilities`, `agent-android-use` and `agent-exec-js` become `compileOnly` behind their
  `BuiltInFeatures` switches, so a host that leaves them off does not carry them.
- Whether tier 2 stays in `agent-app-server` or becomes an artifact of its own.
