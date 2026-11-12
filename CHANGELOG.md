# Changelog

All notable changes are listed here. The project is 0.x: a minor release may break the API, and every break is
called out; a patch release does not break it.

## Unreleased (0.5.0)

- **The app server (experimental).** Two new artifacts, in `agent-bom`: `agent-app-protocol` (plain JVM) and
  `agent-app-server` (Android) make an Android app an agent, one runtime behind a JSON-RPC protocol of threads, turns
  and items that every front end reaches as a client (`ServerClient`), with a plugin kernel (`AppServerPlugin`) and
  plugin packages a person installs. Both are experimental and may change in a minor release: their entry points carry
  `@ExperimentalAppServerApi`, whose opt-in, without one, is a warning. The guide for hosts is
  `agent-app-server/README.md`; `samples/agent-app-sample`, `-notes`, `-timer` and `-habits` are apps on it, and
  `tools/agent-plugin` makes plugin packages. A host writes plugins of its own; the first-party ones but the model
  services, operating the screen and the device's tools (below) are source modules of the Bothy app (cards, drawn
  pages, memory, pictures, plans, A2A), not Maven artifacts; their source is published with Bothy's next release.
  Scripts (plugin packages' tools written in JavaScript, and `exec_js`) run in `agent-exec-js`, which a host that wants
  them adds: the server does not carry its native code, about 4 MB of an APK.

- **Operating the screen and the device's tools on the app server come with their modules.** `agent-android-use`
  carries `AndroidUsePlugin` (id `android-use`, package `cc.noharry.agent.androiduse.appserver`) and
  `agent-device-capabilities` carries `DeviceToolsPlugin` (id `device-tools`, package
  `cc.noharry.agent.device.appserver`), each declared in the module's manifest, so an app that depends on the module
  and on `agent-app-server` has the plugin without listing it (`plugins/list`, `plugins/set`,
  `AppServerConfig.ignoredPlugins`). Both are experimental (`@ExperimentalAppServerApi`). Neither module carries the
  server: it is compiled against it only, so their published dependencies are unchanged, and an app with the runtime
  alone never loads the plugin (CI runs each module's tests where the server is missing, `testWithoutAppServer`). Their
  consumer rules tell R8 not to look for the server's classes. `agent-device-capabilities` gains three string
  resources (`device_tools_calendar`, `device_tools_notifications`, `device_tools_media`, English and Chinese), the
  rows' titles, which an app replaces with its own; the tool servers' ids are `device_calendar`,
  `device_notification` and `device_media` (`DEVICE_*_TOOL_SERVER`), that of the screen's tools `android_use`
  (`ANDROID_USE_TOOL_SERVER`). The screen's plugin lists its accessibility service with the system and repairs one the
  system never binds only where the app may write secure settings.

- **The model services (experimental).** A third app server artifact, `agent-app-models`, in `agent-bom`: one plugin
  (`ModelsPlugin`, id `models`) with an adapter for each of DeepSeek, OpenAI, Anthropic, Google Gemini, xAI,
  OpenRouter, Groq, Moonshot (Kimi), Zhipu (GLM), Alibaba Cloud Model Studio (Qwen), Volcengine Ark (Doubao), MiniMax
  and SiliconFlow: their endpoints and key pages, their models' facts (context window, output limit, pictures, tools,
  reasoning and its efforts) and prices (with currency and date), their model lists and how their failures read, from
  each service's official pages as of 2026-11-11 (the data file names each page). DeepSeek's extras (its search and
  fetch APIs, native updates, balance, the check of a relay, one key for its profiles) moved here from Bothy. A library
  declares a plugin in its manifest and the server loads it without the host listing it (`PluginDiscovery`;
  `AppServerConfig.ignoredPlugins` leaves one out): an app that depends on `agent-app-models` has the services. The
  server itself (`agent-app-server`, `agent-app-protocol`) names no service: with it alone a profile reaches any
  compatible service, with conservative defaults (a 128K window, pictures on, no base URL or model until given one).
  A host's `AppServerConfig.modelFacts` and `modelPrices` win over a service's; `ProviderAdapter` is the contract
  (`services`, `facts`, `price`, `listModels`, `classify`, `balance`, `check`), and `services/list`,
  `services/balance`, `services/check` and `services/checked` the requests. `ModelPrice` has a `currency` (USD or CNY;
  another in a host's `modelPrices` is a configuration error), `asOf`, `region` (Moonshot, Zhipu, Model Studio, MiniMax
  and SiliconFlow have China and international lists, picked by the endpoint a model ran at) and `effectiveFrom` (a
  change a service announced applies from its day); `ModelUsage.cost(price)` is in the price's currency and
  `PriceBook.costUsd(model, usage)` converts yuan. The price book keeps only typed and models.dev prices. `ModelFacts`
  has `requestOutputTokens`, sent as a request's output limit (Volcengine Ark's answers stop at 4,096 tokens without
  one), which a person may set per model (`ModelSettingsView.outputOverride`). A discovered plugin whose needs the app
  does not meet is left out, and one that fails to start stays off, with its `PluginView.problem`.

- **The app server's profiles have no built-in service (behaviour change).** A profile with no base URL or model no
  longer takes OpenAI's (`https://api.openai.com/v1`, `gpt-4o-mini`) or Anthropic's (`https://api.anthropic.com/v1`,
  `claude-3-5-haiku-latest`): without `agent-app-models` it has none until given one, and is not ready to run. With
  it, a profile whose id is a service's (`openai`, `anthropic`, `deepseek`, …) takes that service's default endpoint,
  unless the host named one, and there its default model (`ModelService.defaultModel`), and shares the key of profiles
  on DeepSeek's host. A host that relied on the old defaults sets the base URL and model.

- **A saved key goes only where its profile points.** Requests about a connection (`services/balance`,
  `services/check`, `services/checked`, the model list, saving the connection) send a key typed in them where they
  say, and a saved key only to its saved profile's host, or to an address a check made with that key typed found, over
  https (or plain http to local addresses); elsewhere nothing is sent and the answer is `MODEL_KEY_TYPE_AGAIN`
  (`ServiceCheckView.savedKeyRefused` for a check). `ProviderAdapter.balance`, `check`, `checked` and `listModels` take a
  `ConnectionKey`, read only for the address it goes to.

- **A model service's failures say what they were.** `RuntimeErrorCode` gains `PROVIDER_QUOTA_EXCEEDED`,
  `PROVIDER_CONTEXT_WINDOW_EXCEEDED` and `PROVIDER_TIMEOUT` (new constants: a host's exhaustive `when` over the codes
  needs a branch for each). The app server gives them, and `PROVIDER_AUTH_FAILED`, `PROVIDER_RATE_LIMITED` and
  `PROVIDER_SERVER_ERROR`, to a failed model call as the wire protocols' statuses and error codes say, or as the
  service's own error pages say where `agent-app-models` knows it; a quota, context window or key failure is not tried
  again, except a 429 on a host no adapter knows, which is tried again as the runtime decides. The runtime's own
  clients report HTTP failures as before.

- **A tool provider that throws anything fails only its call.** A `ToolProvider.callTool` that throws an `Error`
  (`UnsatisfiedLinkError`, `NoClassDefFoundError`, `ExceptionInInitializerError`) or a checked exception fails that
  call as a `RuntimeException` did: the model reads `BACKEND_CALL_FAILED` with the error's kind and message, and the
  run goes on. In 0.4.0 the run failed. So does an `ExecEngine.run` (`EXEC_FAILED`), and a provider's `listTools` or
  `initialize` that throws counts as having no tools. Cancellation and `OutOfMemoryError` still end the run.

- **`agent-exec-js` runs no script where QuickJS would misread numbers.** QuickJS reads numbers with the C library's
  `strtod`, which follows the process's numeric locale: a JVM started under one that writes 0,5 read `12.5` as `12`.
  `ZiplineExecEngine` checks once per process and, there, answers each run with `EXEC_ENGINE_NOT_CONFIGURED` saying to
  start the JVM with `LC_NUMERIC=C`. Android reads numbers the same in every locale. A QuickJS that does not load
  (Zipline's Android build under a JVM) answers so too, where it threw an `Error`.

- **A remote A2A task that refuses is `REJECTED`, not `FAILED`.** `A2aTask.Status` gains `REJECTED` (a new
  constant: a host's exhaustive `when` over the statuses needs a branch), terminal like `FAILED`; the direct client and
  `agent-a2a-client-official` report a task in the `rejected` state with it, where both reported `FAILED`. On the
  serving side, `A2aOutput.Result` says what its states mean: `COMPLETED`, the task ended with an answer, which may
  say that part of it was not done; `REJECTED`, the agent found it cannot proceed.

- **Agent Client Protocol.** The new optional module `agent-acp` serves a runtime's threads to ACP clients (editors
  such as Zed): `AcpAgent(runtime).serveStdio()`, or `serve(StreamAcpTransport(input, output))` over any stream pair.
  Sessions are threads, prompts are turns, approvals are permission requests and the session modes are the approval
  policies; `session/load` replays a stored conversation. The client's file system, terminals and MCP servers are not
  used. `samples/acp-jvm` is an agent a client starts as a subprocess.

- **The device tools say which of them only read.** No tool of `agent-device-capabilities` declared an `effectKind`,
  so all were `UNKNOWN`: a host that asks before any tool that does not say it only reads asked even to read the media
  status or search the calendar, and those calls never ran beside other reads. `media.status` and
  `calendar.search_events` are now `READ_ONLY`: they join a round's parallel batch of read-only calls (each device
  provider still takes one call at a time) and such a host no longer asks about them. `calendar.create_event`,
  `notification.create`, `media.control`, `media.play_from_search` and `media.volume` (whose `up`, `down` and `set`
  change the volume) are `MUTATING`, so such a host still asks; with their `ADVISORY` completion impact the runtime
  runs them as before. `agent-android-use` already declared its tools (`android_observe` read-only, `android_action`
  mutating) and is unchanged.

- **A tool whose provider comes back works again in the same conversation.** With session tool activations
  (`sessionToolActivations`, `sessionActivationInheritance`), a turn whose providers did not include a tool the
  conversation had found dropped it for good: once the provider was back, calls were refused with
  `INACTIVE_BACKEND_TOOL` ("find it with search_tools") although earlier turns still showed its schema, and while it was
  away the same words sent the model to a search that could not find it. The conversation now keeps the tool in its
  place: while its provider is off, unreachable or removed, a call is refused with the new
  `RuntimeErrorCode.TOOL_UNAVAILABLE` (a new constant: a host's exhaustive `when` over the codes needs a branch), whose message says that searching or calling again will not help and to tell
  the person; the first call of a turn that has the provider again runs. With `BackendTools.SEARCH` a turn also carries
  the tools whose schema the conversation's earlier turns show, so a conversation resumed from storage after a restart
  calls them without searching (behaviour change: a tool a memory hint brought is carried there once shown). With
  `NATIVE_DEFERRED`, a tool loaded earlier whose provider is missing is refused the same way. A turn without any tool
  provider refuses carried tools as unavailable, where it sent the call on and failed with
  `CAPABILITY_BACKEND_NOT_CONFIGURED`. With `BackendTools.DIRECT`, a restart still needs a search, as before.

- **A hook can have the person answer every time.** `HookDecision.RequireConfirmation(reason, prompt, alwaysAsk = true)`
  asks under ASK, REMEMBER and APPROVE_ALL alike; DENY_ALL refuses it without asking, as before. No remembered area
  answers it, and a yes the person gave to another call earlier in the turn does not carry to it. A yes covers that call
  alone and is never remembered: `ApprovalScope.THREAD` counts as ONCE, and `Approval.alwaysAsk` tells a client to offer
  no "always allow". A subagent has nobody to ask, so such a call in a child is refused whatever policy it inherited. The
  ACP agent sends a permission request for it in every mode, approve-all included, with allow once and reject only.
  When several hooks ask about one call, it asks every time if one of them says so. The data class gains a third
  component: two-argument construction still compiles from Kotlin and Java, while compiled callers of `copy` and Java
  source calling `copy(reason, prompt)` break.

- **An approved call that fails goes back to the model.** A tool call the user approved and that then failed used to
  end the turn as failed, with the tool's error as the turn's; the model now reads the failure like any other and
  answers for it in the same turn. The approval still covers that one call: another attempt that needs one asks again.

- **No failed tool result is a service's answer by default.** `LoopSettings.serviceAnswer` defaults to
  `ServiceAnswerCheck.NONE`; it took MCP results with a `structuredContent.status.name` before. A host whose services
  answer that way sets `LoopSettings(serviceAnswer = McpStatusServiceAnswerCheck)`.

- **Model-history byte guards include multimodal and reasoning data.** Images, reasoning, thinking signatures,
  assistant text and deferred-tool metadata count alongside message text when old runs are trimmed. Past the guard,
  earlier runs lose their images before whole runs are dropped, and the newest run always stays, so one turn with
  large images does not leave the next turn without the conversation.

- **Conversation snapshots are stored once per turn.** A small metadata file serves thread listing; each result has
  its own file, and visible snapshots and provider history share content-addressed image blobs. Disk-backed threads
  release complete result objects after saving. Earlier development snapshots migrate from the inline layout on load.

- **Thread deletion drains background children.** Their jobs are cancelled and joined before storage is removed;
  queued reports are discarded so a late child cannot report into a recreated conversation with the same id.

- **Deleting a thread clears its complete runtime data.** After stopping its turns, deletion removes conversation
  snapshots and quarantined histories, execution mappings, event logs and attachments, SQLite rows, reliability
  artifacts, loop history and its saved plan. All runtimes sharing its storage clear their cached state. Shared
  workspaces and global memory are preserved. A custom event/session store that cannot delete causes an explicit failure.

- **Subagents inherit the spawning turn's approval policy.** `SubagentSpec.approvalPolicy` includes per-turn
  overrides. Child runtimes sharing `Subagents` use it as their default; custom launchers should pass it explicitly.
  Approval gates without a user channel are denied immediately unless automatically allowed. The coordinator also
  answers gates emitted by custom launchers using the inherited policy. Children remain unable to ask the user
  after approval or denial resumes their loop.

- **Denied approvals continue the same turn.** Both explicit denial and `DENY_ALL` return a
  `CONFIRMATION_DENIED` tool result to the model without executing the tool, so it can explain the outcome. An
  approval nobody answers in time does the same with `CONFIRMATION_EXPIRED`, so what the user said while it waited
  reaches the model instead of being lost with the turn.
  Cancelling or interrupting a pending approval records `CONFIRMATION_CANCELLED` in provider history.

- **Session storage backends support complete deletion.** SQLite removes the session's seven conversation tables
  in one transaction while preserving the shared memory index. Reliability storage removes run directories and
  shared evidence, and loop history can be deleted from disk and memory.

- **Event logs can delete complete conversations.** `SessionEventLog.deleteThread` has an unsupported-by-default
  implementation for custom backends. Built-in logs delete events, attachments, health and cached identities;
  asynchronous logs drain accepted writes first and reset subscribers before a thread can be recreated.

- **Notices include structured presentation values.** `Notice.details` carries retry ordinals/delays, remaining
  budgets, compaction counts and workspace ids. Hosts can localize notices without parsing the English message.

- **Approval areas and sources are precise.** Remembered network approvals use the network backend's canonical
  host parser; invalid targets are never remembered. MCP approvals cover one tool, with separate namespaces for
  web, MCP and other tools. `ToolCall.approvedBy` now uses `ApprovalSource.USER`, `REMEMBERED` or `AUTOMATIC`;
  a denied call has no approval source. Older snapshots with an ambiguous REMEMBER policy leave the source unset.
  Within one turn, a web host the person approved is not asked again for later fetches.
  A call an earlier turn left waiting for approval (the process died, or a newer turn replaced it) reaches the next
  turn's model as cancelled instead of still waiting.

- **Damaged history does not block unrelated conversations.** Thread listing skips unreadable or unsupported
  metadata. Invalid provider history is renamed to a `.corrupt` file and the conversation resumes with empty model
  history. Deleting a conversation does not parse that history first.

- **Conversation storage preserves display text and valid tool JSON.** User input and final answers are stored
  verbatim. Tool payloads retain JSON numeric/boolean/null types while sensitive fields and Bearer credentials are
  removed; a JSON document carried inside a string (as MCP text content often is) is filtered the same way and stays
  valid. Phone and location filtering follows the configured redactor for string values; numbers stay numbers
  (timestamps and ids would otherwise read as phone numbers). Provider model history intentionally
  preserves original messages, reasoning and images, including secrets, for accurate replay.
- **Configured event-log locations survive `storage(...)`.** Android continues using its existing `event-log`
  directory. A storage directory must be used by only one process; multiple runtimes in that process coordinate.

- **Repeated provider tool ids do not collide with conversation items.** Tool items use turn-local ids, duplicate
  calls within one response retain distinct tool-result associations, and asynchronous questions use independent
  batch ids. OpenAI chat responses without tool ids receive unique fallback ids in both streaming modes.

- **Steering preserves input identity.** `SteerOptions` lets a host supply the request id echoed in
  `ThreadItem.UserMessage`, including after restart. Ordinary steering never answers a pending question;
  only an explicit `answering` question id does. Inputs can queue while a tool awaits approval.
  Custom `AgentThread` implementations implement the options overload; the two convenience overloads delegate to it.

- **Turn ids are local to a thread.** Every execution gets an independent id, durably mapped to its public turn id
  and attempt number. An unsaved attempt after process loss is interrupted before retrying with a fresh execution.
  Diagnostic result/state/trace queries now require `(threadId, turnId)` and select its latest attempt; raw diagnostic
  `runId` identifies an execution, while `sessionId` and `requestId` retain the public thread and turn ids.

- **Named conversation error codes.** `TurnErrorCodes` documents the codes used by thread admission, interruption,
  storage, questions, attachments and approvals. Provider/tool codes remain available through `RuntimeErrorCode`.

- **Internal assembly and VFS helpers are not host API.** Workspace ports, hook registrations, VFS snapshot/mount
  resolution helpers, and an accidentally generated completion-gate overload are excluded from the public surface.
  Hosts continue to supply `VfsFileSystem` workspaces and register hooks through the builder.

- **Turns finish after releasing execution.** Completion hooks and terminal events may immediately start another
  turn or delete the thread. Interrupted and deleted turns emit one complete failure sequence; storage failures
  report `STORAGE_FAILED`, and successful turn snapshots are written once.

- **Thread option patches preserve saved approval policy.** Null values on resume retain saved defaults;
  `ThreadOptions.reset` clears selected model, workspace, scene or instruction overrides. New threads still use ASK.
  A policy given to `resumeThread` while a turn runs applies to that turn at once: an approval it is waiting for is
  decided by the new policy (`APPROVE_ALL` approves, `DENY_ALL` denies, `REMEMBER` approves a remembered area).
  `startThread` and `resumeThread` do their small metadata I/O before returning (call them off the main thread on
  Android); a resume that changes nothing no longer rewrites the saved thread.
- **Independent budgets survive settings changes.** `LoopSettings.limits` defaults to null, so a settings call does
  not reset limits configured through `limits(...)`. An explicit settings budget still replaces them.

- **Stable budgets, experimental advanced tuning.** `LoopLimits` is stable. Narration and service-answer checks,
  memory/concurrency/tool-exposure tuning, and structured event-log payloads remain experimental.
- **Java event-log factory.** Open a durable log with `SessionEventLogs.openFileBackedSessionEventLog(File)`.
  The Kotlin top-level function keeps its name; the old plugin-named JVM facade is removed.
- **Runtime contracts belong to `agent-api`.** Libraries that accept `AgentRuntime` or its experimental diagnostics
  no longer need the runtime implementation. Kotlin construction imports `cc.noharry.agent.builder` from
  `agent-runtime`; Java uses `AgentRuntimeBuilder.create()`. Implementation-specific prompt and tool catalog
  helpers move from `RuntimeDiagnostics.Companion` to `cc.noharry.agent.runtime.RuntimeInspection`.

- **Breaking: Thread / Turn / Item replaces the run API.** Import the stable conversation contracts from
  `cc.noharry.agent`: start or resume an `AgentThread`, call `run` for a `TurnResult` or collect `runStreamed`.
  Eight event types replace the old event union. Items have stable ids and complete final snapshots;
  `AgentMessage.phase` distinguishes commentary and final answers.
- **Breaking: module and package names.** `agent-kernel-api` is now `agent-api`; extension interfaces move from
  `cc.noharry.agent.kernel.api` to `cc.noharry.agent.spi` (including `.model` and `.mcp`). The kernel implementation
  and its tests are part of `agent-runtime`; `agent-kernel-core` is no longer published. Plugin assembly and the old
  request/event machinery are internal. Protocol-request scaffolding is removed.
- **Approval stays in the original stream.** Respond with `Approval.approve()` or `deny()`; pending requests are
  also accessible through `thread.pendingRequests()`. Policies `ASK`, `REMEMBER`, `APPROVE_ALL`, and `DENY_ALL` can
  be configured per thread or turn. Remembered areas survive runtime recreation when storage is configured.
- **Questions and input.** `ask` ends a turn with `WAITING_FOR_ANSWER`; the next turn answers it, optionally using
  `TurnInput.answering` to bind explicitly. Asynchronous questions accept `thread.steer` during the turn.
  Input can include images and attachment references. An unsupported image fails with `IMAGE_INPUT_UNSUPPORTED`.
- **Durable conversations.** `.storage(directory)` persists model history and item snapshots; Android defaults to
  app-private storage. `resumeThread` restores history and `items()` redraws the conversation after a restart.
  Live tool arguments and results are original; stored snapshots and diagnostic records use redaction.
- **Breaking: host configuration.** A model is required at `build()`. Memory topics default to empty, the standard
  narration check no longer assumes Chinese phrasing, and protocol text no longer selects a search site.
  Hosts set their own policies explicitly. MCP server ids containing underscores are grouped correctly.
- **Breaking: model and hook SPI.** `ModelMessage.reasoningContent` carries replayable reasoning;
  `ServerSideSearchCapability` moves to `cc.noharry.agent.model`. Hooks use `onTurnStarted` / `onTurnFinished`,
  and subagent launchers return `Flow<ThreadEvent>`. Diagnostic access uses experimental `RuntimeDiagnostics`;
  prompt/catalog inspection uses `RuntimeInspection`. Advanced tuning types move to `cc.noharry.agent.runtime`.

### 0.5.0 migration

Rebuild host code against 0.5.0. The module/package changes and constructor changes are binary breaks.
Kotlin hosts import `cc.noharry.agent.builder`; Java hosts construct with `AgentRuntimeBuilder.create()`.

The public API dumps total 5,253 lines across the published modules, down from about 9,400: `agent-api` 2,658
(the conversation API in `cc.noharry.agent` 770, extension interfaces the rest) and `agent-runtime` 1,514.
Most of what remains beyond the conversation API is the session event payload types and the workspace file system
that hosts implementing their own storage or workspaces need.

| 0.4.x runtime entry | 0.5.0 entry |
|---|---|
| `run(AgentRequest)` | `startThread` / `resumeThread`, then `run` / `runStreamed` |
| `close` | `close` |
| `resumeConfirmation` | `Approval.approve` / `deny`; keep collecting the original turn |
| `getPendingConfirmation`, `getOpenConfusion` | `thread.pendingRequests()`, `TurnResult.pending` |
| `cancelPendingConfirmation` | `thread.interrupt()`, or let the approval expire |
| `resumeUserInput` | Next turn's `run`; `TurnInput.answering` can name a blocking question |
| `postUserInput` | `thread.steer`; name an async question in `SteerOptions.answering` to answer it |
| `getRunState` | `thread.status()` |
| `getRuntimeResult` | `TurnResult` from `run` or `TurnCompleted`; saved visible history through `items()` |
| `getRuntimeHandoff`, `getRuntimeEvidence`, `getRuntimeTraceEvents` | `runtime.diagnostics`; turn queries take `(threadId, turnId)` |
| Event-log query/replay/subscribe/health methods | `runtime.diagnostics`; screens normally consume `thread.items()` |
| Protocol-request methods | Removed; expose a host tool instead |
| `KernelRuntimeBuilder`, `CoreAgentRuntime` | `AgentRuntime.builder()` / `AgentRuntimeBuilder.create()` |

| 0.4.x `AgentEvent` | 0.5.0 event or item |
|---|---|
| `Started` | `TurnStarted` |
| `UserInteraction` | `Question` item lifecycle; accepted input is a `UserMessage` |
| `ThinkingDelta` | `ItemDelta` on a `Reasoning` item |
| `TextDelta` | `ItemDelta` on an `AgentMessage` item |
| `TokenUsageDelta` | Cumulative `UsageUpdated`; final totals in `TurnResult.usage` |
| `ConfirmationRequired` | `ItemStarted(Approval)` |
| `RuntimeBlocked` | `Question` plus `TurnCompleted(WAITING_FOR_ANSWER)`, or `TurnFailed` |
| `RuntimeProtocolRequestCreated` | Removed from the host API |
| `RetryAttempted` | `Notice(RETRY)` with structured details |
| `BudgetWarning` | `Notice(BUDGET_LOW)` with structured details |
| `CompactionApplied` | `Notice(COMPACTION)` with structured details |
| `EmptyCompletionObserved` | `diagnostics.getRuntimeTraceEvents(threadId, turnId)` |
| `SteeringAccepted` | `ItemCompleted(UserMessage(steer = true))`, including its request id |
| `NativeToolCallStarted` | `ItemStarted(ToolCall)` |
| `NativeToolCallFinished` | `ItemCompleted(ToolCall)` |
| `NetworkToolActivity` | `ItemUpdated(ToolCall)` with `network` metadata |
| `SearchActivity` | `WebSearch` item |
| `ExecOutputDelta` | `ItemDelta` on a `ToolCall` item |
| `WorkspaceFileChanged` | `FileChange` item |
| `WorkspaceChanged` | `Notice(WORKSPACE_CHANGED)` with a workspace id |
| `ResultAvailable`, `Finished` | `TurnCompleted` |
| `Failed` | `TurnFailed` |

| 0.4.x configuration | 0.5.0 configuration |
|---|---|
| `agent-kernel-api`, `agent-kernel-core` dependencies | `agent-api`, `agent-runtime` |
| `cc.noharry.agent.kernel.api` imports | `cc.noharry.agent` for conversation contracts; `.spi`, `.spi.model`, `.spi.mcp` for extensions |
| `modelClient`, `toolProvider(s)`, `systemPrompt`, `skill` | `model`, `tools`, `instructions`, `skills` |
| `loopSettings` | `settings(LoopSettings(...))` |
| `loopBudget`, `contextWindow`, `modelRetry` | `limits(LoopLimits(...))` |
| `imageInput`, deferred tool flags, `runContextAsSystem`, `lessons`, native tool flags | Corresponding `LoopSettings` fields |
| Hook/model/tool/session concurrency setters | `LoopSettings.hookTimeoutMillis` / `RuntimeConcurrency` |
| `sessionStore`, separate durable history setup | `storage(directory)`; Android configures SQLite and app-private storage automatically |
| `profile`, `plugin`, `nowEpochMillis` | Removed from the host API; composition/test facilities are internal |
| Workspace, hooks, context providers, observers, `sessionHistory`, subagents, search, policy and evidence setters | Retained; advanced configuration remains experimental |
| Android `DurableSessionEventLogPlugin.appPrivate` | `appPrivateSessionEventLog(context)` |
| Java file-backed event-log factory | `SessionEventLogs.openFileBackedSessionEventLog(File)` |

0.4.0 history is not imported into the new conversation store: old sessions do not appear in `listThreads()` and
have no old items in `items()`. Existing SQLite v5 data is preserved; starting a conversation with an old session id
continues writing to that SQLite session. Old event-log records can be read with the low-level
`diagnostics.querySessionEvents(SessionEventQuery(sessionId, runId = originalRunId))`; they have no execution mapping
for the new turn-based diagnostic methods. Provider history in 0.4.0 was memory-only. Applications that keep their own
transcript can continue displaying it.


### Other 0.5.0 changes

Public constructors for model clients, A2A, Android Use, HTTP/MCP transports and `SessionHistory` gained options;
recompile their callers. Kernel loop, scheduler, tool-registry and prompt internals are no longer host API.

- **The probe emitter hub is gone** (`RuntimeProbeEmitterHub`, `RuntimeProbeStageEmitter`, `ProbeEmitterRegistration`).
  The runtime never installed an emitter, so `emitterFor` always returned null. Probe evidence
  (`runtimeEvidence(RuntimeEvidenceConfig(…))`) is unchanged.
- **Java hosts write only what they change.** Interface methods with a body now compile to JVM default methods, so a
  Java `ModelClient` implements only `complete`, and a Java `RuntimeHook` only the callbacks it uses. The `DefaultImpls`
  classes remain available for Kotlin compatibility within the new package layout; the 0.4.x package migration
  still requires rebuilding hosts.
- **`agent-a2a` is a plain JVM library** (a jar, not an Android library), so a server or desktop host can use the A2A
  client and server too. An Android app that uses it without `agent-a2a-client-official` now
  declares the `INTERNET` permission itself, which the library's manifest used to add.
- **A2A on Android 6 (API 23).** The direct and official A2A clients' failures were made with a constructor Android only
  has from API 24, and request headers were copied with `Map.forEach` (API 24 too): on Android 6 a refused endpoint
  crashed the host instead of returning a failure, and requests failed without core library desugaring.
- **API docs for the JVM artifacts.** The javadoc jars of `agent-api` and `agent-runtime`
  were empty; they now hold the API docs, like the other artifacts'.
- **Live conversation content is original.** `AgentMessage`, `Reasoning`, `ToolCall` and `Question` snapshots carry
  live content without storage redaction. User input and final answers remain original in saved visible history;
  tool payloads and diagnostic records follow the configured redactor. Hosts that want different display rules
  apply them to the live text. Provider model history is stored verbatim, including signed reasoning and images.
- **A question that looks like a key no longer ends the run.** The durable event log refuses text that starts like a
  credential (`sk-`, `Bearer `), and a question card (`ask_async`) whose title or option did so failed the run: it was
  the one record not left out of the log when refused. It now is, and the card still reaches the host. Likewise a
  user's reply that starts like a key (`thread.steer`) is now accepted and reaches the model, left out of the log,
  where it was refused with an exception.
- **A runtime without a workspace reaches its tools.** Tools from providers (`functionTools`, MCP) are found with
  `search_tools`, which only a workspace ran: without one, the model was offered `search_tools` and the file tools, all
  answering `WORKSPACE_NOT_CONFIGURED`, and could call only the tools a `ToolPrefetchProvider` had brought in.
  `search_tools` now works without a workspace. Such a runtime no longer offers the file tools (`list_dir`,
  `read_file`, `grep`, `write_file`, `edit_file`), nor the prompt's lines about files, its context paths or its skills,
  which the model would have to read as files (behaviour change: its prompt and tool list are shorter). A backend tool
  called before it was found now says to find it with `search_tools`.
- **The Dream scaffold is gone** (`cc.noharry.agent.dream`: `DreamWorker`, `DreamRequest`, `DreamMode`, `DreamResult`,
  `NoOpDreamWorker`, `MemoryIndexRebuilder`). Nothing in the runtime ran it. A host that called `MemoryIndexRebuilder`
  to rebuild `/memory/MEMORY.md` from the memory files writes that index itself.

- **Claude's thinking.** Opus 4.7 and later, Sonnet 5 and later, Haiku 5.5 and newer families refuse a `budget_tokens`
  with a 400, and Opus and Sonnet 4.6 deprecate it. The `low`, `medium`, `high` and `max` variants now send
  `thinking: {type: adaptive}` with `output_config.effort` to every Claude from 4.6 (also written `4.6`, as gateways
  do), asking for summarized thinking from 4.7, which otherwise comes back empty; they offer no `none`, since some refuse
  turning thinking off. Claude 4 below 4.6 (Haiku 4.5 now too) and 3.7 Sonnet keep the thinking budget; earlier Claude
  is offered no thinking variant.
- **Anthropic-format output limit.** Requests to models other than DeepSeek's sent `max_tokens` 1024 (an older
  Claude's thinking budget plus 1024 with a variant), cutting longer answers and tool arguments off. The default is now
  16000, with an older Claude's thinking budget on top (an adaptive Claude's thinking shares it), and
  `AnthropicModelClient(maxOutputTokens = …)` sets it; a compatible service with a lower output cap needs it set. Kotlin
  source that does not name the new parameter is unchanged; Java callers and old binaries of that constructor need the
  new signature.
- **OpenAI-format output limit.** `OpenAiModelClient(maxOutputTokens = …)` sets the output limit: `max_completion_tokens`
  on OpenAI's own API (its reasoning models refuse `max_tokens`), `max_tokens` on compatible services,
  `max_output_tokens` on Responses. Without it a DeepSeek model, known by its name, still asks for DeepSeek's own limit
  and other models send none; a gateway that serves a DeepSeek model with a lower cap needs it set.
- **Anthropic server-side search is on only for Anthropic's own API** unless `serverSideSearch` is set (behaviour
  change): compatible services do not know the `web_search_20250305` tool. A host that reaches Anthropic through a
  gateway passes `serverSideSearch = true`. A request whose tools already have a `web_search` (a host's own, through
  `AgentRuntimeBuilder.webSearch`) leaves the server tool out, as two tools of one name are refused.
- **OpenAI server-side search is off unless asked** (behaviour change). `OpenAiModelClient` sent `web_search_options`
  (Chat Completions) or the `web_search` tool (Responses) by default; OpenAI refuses `web_search_options` on models other
  than its search models. Hosts that relied on it pass `serverSideSearch = true`.
- **HTTP calls end with their caller.** `OkHttpHttpTransport` ran blocking calls that a cancelled coroutine did not
  stop, so a timeout (an MCP server's 10 s) or a stopped run waited for OkHttp's own 10 s between bytes. A cancelled
  caller now cancels the call at once, and the read timeout is 300 s, so a non-streamed answer that takes longer to
  write, or a thinking model that says nothing for a while, is not cut off; HTTP/2 pings every 30 s find a dead
  connection.
- The default Claude model, used when none is named, is `claude-haiku-5-5` (`claude-3-5-haiku-latest` was retired).
- **Who may use the A2A server.** It listens on the loopback address, where every app on the device reaches it.
  `A2aServerConfig(authorizer = A2aAuthorizer.bearer(token))` lets only callers with that bearer token send messages or
  read tasks (the Agent Card stays readable and declares the scheme); `A2aAuthorizer { headers -> … }` checks anything
  else, and one that throws refuses. A refused caller is answered as soon as its headers are read, and a request must
  arrive whole within 10 s. `bindAddress` (an IP address) listens elsewhere, which needs an authorizer and an `https`
  `publicUrl`, since the server speaks plain HTTP and belongs behind a TLS tunnel or proxy; `publicUrl` is the address
  the card gives and `cardVersion` the agent's version on it. Without them nothing changes.
- **Only DeepSeek's text is read for written tool calls.** Text in DeepSeek's DSML markup became tool calls whatever
  the model, so every Anthropic-format stream held each text block back until it ended. Now only DeepSeek's text is
  read that way (a model named `deepseek…`, or any model on a `deepseek.com` endpoint); a Claude's text streams as it
  comes.
- **Streamed line breaks are kept.** In OpenAI Chat Completions and Responses streams a text or thinking piece that was
  only a line break or spaces was dropped, running lines and paragraphs together. A reply the model writes without
  `finish` is trimmed, so the line breaks a stream may open with are not part of it.
- **A request's variant counts whatever the request calls the provider.** It was used only when `ModelRef.providerId`
  was `OpenAI` or `Anthropic` (in any case), so `ModelRef("deepseek", "deepseek-v4-pro", "high")` ran with the model's
  default (DeepSeek's thinking off). It now applies whenever it names the model being called, sent as the host chose it.
  `ModelVariantRegistry.resolve` still keeps only variants it knows for the `providerId` given.
- **Host content judgements and loop timing.** `settings(LoopSettings(...))` configures narration, memory topics,
  service-answer checks, context-provider timeouts, images, tool exposure and concurrency. `limits(LoopLimits(...))`
  sets round/tool/time/retry/context budgets and loop protection thresholds. The default memory topic list is empty;
  host-specific language checks and search-site preferences are explicit. `toolDomains(...)` configures area naming,
  and `protocolRules(...)` configures protocol wording. `SessionHistory` remains available for host-owned caches;
  `.storage(...)` adds durable history without a host recap.
- **A host's own HTTP timeouts, proxy and sandbox limits.** `OkHttpHttpTransport(connectTimeoutMillis,
  readTimeoutMillis, proxy)` sets how long connecting (10 s) and a wait between bytes (300 s) may take and the proxy calls
  go through; a host that needs more of its HTTP client (interceptors, certificate pinning) implements `HttpTransport`
  over its own. `McpHttpClient(exchangeTimeoutMillis = …, initializeTimeoutMillis = …)` sets how long a request
  (30 s) and opening a session (10 s) may take. `ZiplineExecEngine(maxToolCalls, memoryLimitBytes, maxStackSizeBytes)`
  sets how many tool calls one `exec_js` script makes (32; 0 for none) and QuickJS's memory and stack limits (QuickJS's
  own when not set; the stack at most 512 KiB, as QuickJS runs on the calling thread).
  `AndroidAgentRuntime.builder(context, name)` keeps a second runtime's workspace, sessions, event log and lessons under
  `agent-runtime/runtimes/<name>/`. `sceneAgentsMd(scene, content)` now puts its content in the system prompt of runs
  in that scene (it only said that scene rules applied, and the content was not used), up to 16 KiB and not counted
  against the prompt's budget for tool areas and skills; blank content removes it.
- **Tool search reads Japanese, Korean and Thai.** Text in a script whose words run together or carry their particles
  (Japanese kana, Korean, Thai, Lao, Khmer, Myanmar, and the rarer Chinese characters) is read in pairs of characters
  as Chinese was, so `コーヒー` or `조명` finds the tool whose description holds it; English and common Chinese are read as
  before.
- **Android Use's own rules can be set** (`AndroidUseOptions`, defaults as before): `fallbackOnly` (the tools tell the
  model to look for a dedicated tool first), `protectSystemBars` (the status bar and dock stay out of reach),
  `observeOwnApp` (the host's own app windows are seen too, never its overlays), `jsonDescriptionActions` (a JSON
  contentDescription is read for its `action` label) and `leaseIdleMillis` (how long another conversation waits for the
  screen after this provider's run used it).
  `AndroidUseAccessibility.settingsIntent()` opens the accessibility settings for an app that may not turn the service
  on itself.
- **Device notifications and calendar.** `notification.list` is no longer offered: it always failed, since reading other
  apps' notifications needs the host's own listener (behaviour change). Each `notification.create` posts its own
  notification instead of replacing the last (or one of the host's: they carry a tag of their own), on a channel the
  host may name (`DeviceNotificationMcpProvider(context, channelName, channelId)`; an existing channel is left as it
  is), and before Android 8.0 too. `calendar.create_event` adds to the device's primary calendar it may write, else the
  first, unless `calendarId` names one (finding it needs READ_CALENDAR as well); nothing listed the ids it required.
- **Dependencies users compile against.** `agent-runtime` exposes `kotlinx-serialization-json` (its API takes and
  returns `JsonObject`) and `agent-a2a-client-official` exposes `agent-a2a` (its module is an `A2aClientModule`) as API
  dependencies; 0.4.0 published them as runtime-only, so code using those types did not compile.
- **Nothing in Chinese unless the device is.** Text the runtime sent in Chinese whatever the app (behaviour change):
  - The `finish` tool's description is English.
  - `web_fetch` asked every site for Chinese pages; it now asks in the app's language (language and region), then
    English, worked out for each request.
  - The A2A server's status texts are English, and `A2aServerConfig.messages` words them for the host; the Agent
    Card's `description` and `skillDescription` are the host's too, and no longer call the answers speech.
  - Android Use's accessibility service is named in English, and in Chinese on a Chinese device. Its tools are
    described in English, opening with what makes them a fallback, and the media tools likewise; both add the Chinese
    words a request uses only where Chinese is in use (one of the system's languages, or the app's), for tool search.
  - Compaction no longer asks for a car's state (temperatures, destination, media), and the `search_tools` and
    `spawn_agent` descriptions no longer give car tasks as examples.
- **Memory outside the built-in topics.** A remembered tool set was used only for requests about climate, navigation,
  media, the web, the display or the phone. A request on any other topic now finds a lesson that shares at least two
  words with it beyond the commonest (turn, on, open, status…). Words keep their accented and non-Latin letters, which
  were dropped; a run of Chinese characters is still one word, so a Chinese request outside the topics matches nothing
  by words alone. A lesson's scene, the same host label on every request, no longer counts as one of its words.
- **Hosts choose storage redaction.** The stable `AgentRuntimeBuilder.traceRedactor` accepts a `TraceRedactor`.
  `StandardTraceRedactor(phones = false, locations = false)` preserves phone numbers and coordinates in tool
  snapshots and diagnostics. Credential filtering still applies to tool payloads after custom redaction.

## 0.4.0 (2026-11-01)

Module and package layout, plus fixes to what a session's next request shows. **Breaking** for code and build files that
name what moved.

- **Fewer artifacts.** `agent-vfs` is part of `agent-runtime` and `agent-vfs-saf` of `agent-runtime-android`;
  `agent-a2a-client`, `agent-a2a-client-direct` and `agent-a2a-server` are one artifact, `agent-a2a`. Their packages are
  unchanged (`cc.noharry.agent.vfs`, `.vfs.saf`, `.a2a`, `.a2a.direct`, `.a2a.server`): replace the dependencies, not
  the imports. `agent-minimal` is no longer published; it is `samples/minimal`.
- **`agent-voice` moved out.** It used none of the runtime and now lives in [Bothy](https://github.com/NoHarry/bothy)
  as `bothy-voice`. Releases up to 0.3.0 stay on Maven Central.
- **`agent-kernel-core` packages.** The flat `cc.noharry.agent.kernel.core` is split into `.loop`, `.tools`,
  `.context`, `.state`, `.plugin`, `.memory` and `.trace`; for example `KernelRuntimeBuilder` and `CoreAgentRuntime` are
  in `.plugin`, `NativeToolLoop` in `.loop`. Code that imports kernel-core types needs the new packages.
- **`agent-runtime` packages.** `cc.noharry.agent.runtime` keeps the host-facing API (`AgentRuntimeBuilder`,
  `functionTools`, `SessionHistory`, `SessionToolActivations`, `Subagents`, `HostSkill`, `RuntimeWorkspaceRef`,
  `NetworkToolConfirmationPolicy`, web search); the implementation moved to `.workspace`, `.run` and `.reliability`.
- **What the next request shows.**
  - A cancelled run is kept in the session's history: its calls, a `TOOL_CALL_CANCELLED` result for any that had not
    returned, and a notice that it was cut short. The next run used to start as if it never happened.
  - A run that failed after its calls ran keeps those calls and their results, and ends with a notice naming the error
    code; the error text is no longer stored as the model's own answer.
  - What the model said beside its tool calls in a round (spoken, shown) goes back to it: `ModelMessage.assistantText`,
    sent as the assistant's content (OpenAI), a text block before `tool_use` (Anthropic) or an assistant message before
    the call (Responses).
  - A backend tool's description is sent with its definition (`ModelTool.description`); only its schema went before.
  - Calls run as a parallel read batch, and denied calls, keep the round's thinking signature, which Anthropic requires
    back; a resumed run keeps signatures too.
  - A streamed Anthropic thinking block's signature reaches the loop (an empty `ThinkingDelta` carries it when it
    arrives); before, only non-streamed replies kept it, so a streamed Claude turn went back unsigned.
  - The Responses API's cached input tokens (`input_tokens_details.cached_tokens`) are counted as cache reads.
  - `finish` (or a text-only answer) that only announces a lookup in Chinese, such as 「我将为你查询当前的空调状态。」, is
    sent back for a real answer once, like its English forms.
  - A call waiting for the user's confirmation shows in the session's history as waiting. If the user declines, the
    confirmation is withdrawn or it expires, the call stays there with a `USER_DECLINED`, `CONFIRMATION_CANCELLED` or
    `CONFIRMATION_EXPIRED` result; the next run used to start as if nothing had been asked. An approved call that then
    fails still ends the run, and the next run now sees the call, its result and a notice.
  - The call that waited for confirmation goes back with its round's thinking signature and what the model said
    before it, like any other call.
  - A resumed run (after a confirmation or an `ask` answer) no longer starts with a `Task: … Next: …` system note
    before the user's input: the run's own history already says it all, and the note moved the prompt cache's prefix.
    The note is still used when the paused history is lost.
- **The protocol prompt describes only the tools the runtime offers.** Its `web_fetch` rule (search Bing through it)
  is left out when `web_fetch` is off, and says to search with `web_search` when that is on; the `update_todo` and
  workspace-write rules go when the host disables those tools (`ProtocolTools`). The kernel runtime, which has no
  `web_fetch`, no longer mentions it. Prompt version `v10`.
- **Prompt caching with Claude.** `AnthropicModelClient` asks Anthropic's API to cache each request's prefix (a
  top-level `cache_control`, automatic caching); Claude caches only what a request marks, so requests to
  `api.anthropic.com` were never cached. Other base URLs (DeepSeek and other compatible services, which cache on their
  own) are unchanged; `promptCaching` sets it either way.
- **In-run compaction folds an old read-only result only when that at least halves it.** A result of a few hundred
  bytes was folded into a preview not much smaller, sometimes larger, and every early message it rewrote cost the prompt
  cache for the rest of the run.
- **Tools.**
  - A tool's name always fits what model APIs take (ASCII letters, digits, `_` and `-`, at most 64 characters): a
    name part in another script becomes a short hash of itself, a long name keeps its start and a hash of the whole.
    `mcpQualifiedName` gives that name. A Chinese tool name, or a long one, used to make the provider reject the request.
  - Read-only tools join a round's parallel batch when the provider says so (`effectKind = READ_ONLY`: an MCP tool's
    `annotations.readOnlyHint`, `functionTools(readOnly = true)`); only a `readOnlyHint` inside the input schema
    counted, so MCP's standard hint never did. How many of a provider's calls run at once is still its own
    `providerConcurrency`: `functionTools` calls one at a time.
  - An MCP server over HTTP (`McpToolProvider` with `McpHttpClient`) takes up to four calls at once; it took one, so a
    round's three read-only calls to it ran one after another (1.2 s for three 400 ms calls, now 0.4 s). Calls in
    flight together that find the session lost open a new one once, not once each.
  - Arguments are checked against `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `minLength` and `maxLength` too;
    only `minimum` was, so a 35 went to a 16–32 thermostat.
  - A failure the provider already explains (`{"ok":false,"code":…}`) reaches the model as it is; it used to arrive
    escaped inside a `BACKEND_CALL_FAILED` message.
  - A tool found by prefetch no longer carries its description inside its schema as well; it was sent twice.
  - A real backend tool called before it was loaded is answered `INACTIVE_BACKEND_TOOL` with the schema path to read,
    in every run: the run records the catalog it loads at the start. Without a search or prefetch first, the answer was
    a bare `UNSUPPORTED_NATIVE_TOOL`, as if the tool did not exist.
  - `search_tools` ranks with Okapi BM25 proper: a term counts by how few tools have it, against the catalog's average
    description length, with a light length penalty; a one-character Chinese query (灯) matches the tools that have
    the character. On Bothy's catalog, 「灯」 now finds `set_light` and 「导航去公司」 `start_navigation`, which a
    one-line `stop_navigation` outranked. A remembered capability now preactivates a tool only when the tool has every
    term of it (`queryCoverage`); a fixed bar on the search score no longer fits a score that depends on the catalog.
- **Workspace.**
  - A file the model writes to `/scratch` is read, listed and found by `grep` at that path. Writes went to the run's
    own folder under `/scratch`, but reads did not, so `read_file` of a file just written said not found, and `grep`
    and `list_dir` showed the internal folder (and `grep` other runs' scratch).
  - A directory mounted at the top (`/mcp`, `/context`, `/.agents`) is listed as a directory; it was listed as a file.
- **A subagent cannot ask the user.** Its `ask` paused its run for an answer nobody would give, and the parent saw
  the child fail "without a result", the question lost; its `ask_async` question went nowhere. Both now return
  `INTERACTION_UNAVAILABLE` (go on with a reasonable assumption, or finish with the question), and the child goes on.
  `NativeToolLoop` and `NativeRoundRunner` take `userReachable`.
- **A skill's description is its header's own.** The SKILL.md header reader took indented lines as keys too, so a
  `description:` under `metadata:` replaced the skill's; only top-level keys count now, and a value in single quotes
  loses them as one in double quotes did.
- **The event log records only the assistant's own messages as `ASSISTANT_MESSAGE`.** A tool's result message was
  recorded as one too, its result as the assistant's text and the tool's name as `finishReason`, and so was a system
  message such as the run context; a trajectory read them as things the model said. They are left out (the result is
  the call's `TOOL_RESULT`, the system message is in `MODEL_REQUEST`). An assistant message's call is
  `toolCalls = {"id","name","arguments"}`, and what the model said beside it is its text.
- **The event log's new files and renames survive a power cut.** A segment's first line and each manifest (written to
  a temporary file, forced, then renamed) forced their bytes but not the directory entry, so a power cut could lose a
  new segment or bring back an older manifest, whose segment list then left the newest events out. The directory is
  forced too, where the platform allows it; the health file, advice recomputed on read, is not.
- **`exec_js` replays tool calls in order.** A script runs again from the start after each tool call it waits for;
  answers were cached by tool name and arguments, so a read after a write returned the old value, the same call made
  twice ran once, and a script whose arguments changed on each run (Math.random, the time) ran its tool 32 times before
  failing. Now the n-th call of every run gets the n-th answer, and a run that calls differently stops before any tool
  runs again. A `try`/`catch` around `tools.call` no longer swallows the request to run the tool.
- **Confirmations.** A hook that asks for confirmation again for the call the user just approved (one that does not
  check `HookContext.approvedConfirmation`) is taken as answered; the run used to fail with "Confirmation still
  required".
  - `PendingConfirmation` says what it asks: `prompt` (from the policy rule or hook), `toolName` and `argumentsJson`
    (what runs on approval, after hook rewrites). A host had only the pending id, and had to fetch the prompt from the
    run's handoff and guess which call it was about.
- **Binary break:** `ModelMessage`, `ModelTool`, `RoundOutcome.Failed`, `PendingNativeToolUse`, `PendingConfirmation`,
  `BootstrapPromptProvider.build`, `ContextBootstrapPromptResolver` and `AnthropicModelClient` gained parameters;
  `PendingToolUseController.remove` returns the removed call.

## 0.3.0 (2026-10-25)

- `agent-voice`: the wake word listens with sherpa-onnx's Chinese–English keyword spotter
  (`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`), so a wake phrase may be English, written in the model's phones
  (`WakePhrase("OW1 K EY1 B AA1 TH IY0", "OK_Bothy")`). **Breaking:** an app that ships the wake model ships this one
  in its assets in place of the WenetSpeech one (`WAKE_MODEL_ASSET_DIR`); the Chinese phrases' pinyin tokens are the
  same. `WakePhrase.threshold` and `WakePhrase.boost` set one phrase's threshold and boost apart from the spotter's,
  and a phrase must be reported without spaces. **Binary break:** the new parameters change `wakeKeyword`'s and
  `WakePhrase`'s compiled signatures (constructor, `copy`); source that calls them compiles as before, a library
  compiled against 0.2.0 must be rebuilt.

## 0.2.0 (2026-10-25)

- `agent-device-capabilities`: `DeviceMediaMcpProvider`, media tools for any app with a media session (`media.status`,
  `media.control`, `media.volume`, `media.play_from_search`). With notification access granted to
  `DeviceMediaListenerService`, which the host declares, it sees what plays, picks the app and runs the app's own
  actions; without it, play, pause and skipping go out as media keys. The module's manifest now queries launcher apps,
  to name the app behind each session. `soundPlaying` says whether anything sounds at all, which a video app's
  session may not admit to; a host that knows the app in front passes `foregroundPackage`, and with nothing playing
  and no app named the commands go to it.
- `agent-android-use`: `AndroidUseAccessibility.foregroundApp(context)`, the app whose window is active, for a host to
  tell its agent what is on screen.

## 0.1.0 (2026-10-25)

First public release.

- Kernel (`agent-kernel-api`, `agent-kernel-core`) and the JVM runtime (`agent-runtime`): the agent loop with native
  tool calling, OpenAI- and Anthropic-compatible model clients, a sandboxed virtual workspace, skills, subagents,
  hooks and policy, durable session event logs and conversation compaction.
- Built-in Streamable HTTP MCP client (`McpHttpClient`, `McpToolProvider`) with replaceable messages
  (`McpClientMessages`).
- `functionTools { tool(...) { args -> ... } }` for tools written as Kotlin functions. `ToolProvider` and
  `ModelClient` default to serialized concurrency, so implementing them needs no opt-in.
- `agent-runtime-android`: `AndroidAgentRuntime.builder(context)`, SQLite sessions, the app-private event log.
- Optional modules: SAF mounts, sandboxed JavaScript, device capabilities, Android Use, Mandarin voice (wake phrases
  and the assistant's name supplied by the host), A2A client and server.
- `agent-bom` for version alignment; samples for the JVM and Android.
- `minSdk` 23; below API 26 the host enables core library desugaring with `desugar_jdk_libs_nio`, since the workspace
  and the event log use `java.nio.file`.
- Built at Kotlin language and API level 2.1 against the 2.1 standard library: hosts on Kotlin 2.0 or later can use it.
