# Changelog

All notable changes are listed here. The project is 0.x: a minor release may break the API, and every break is
called out; a patch release does not break it.

## Unreleased (0.5.0)

- **Internal assembly and VFS helpers are not host API.** Workspace ports, hook registrations, VFS snapshot/mount
  resolution helpers, and an accidentally generated completion-gate overload are excluded from the public surface.
  Hosts continue to supply `VfsFileSystem` workspaces and register hooks through the builder.

- **Thread option patches preserve saved approval policy.** Null values on resume retain saved defaults;
  `ThreadOptions.reset` clears selected model, workspace, scene or instruction overrides. New threads still use ASK.
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
  and subagent launchers return `Flow<ThreadEvent>`. Diagnostic access and prompt/catalog inspection are under
  experimental `RuntimeDiagnostics`. Advanced tuning types move to `cc.noharry.agent.runtime`.

### 0.5.0 migration

| 0.4.x entry | 0.5.0 entry |
|---|---|
| `run(AgentRequest)` | `startThread` / `resumeThread`, then `run` / `runStreamed` |
| `TextDelta`, thinking and tool events | `ItemStarted`, `ItemDelta`, `ItemUpdated`, `ItemCompleted` |
| `ResultAvailable` / `Finished` / `Failed` | `TurnCompleted` / `TurnFailed` |
| `resumeConfirmation` / `getPendingConfirmation` | `pendingRequests`, `Approval.approve` / `deny` |
| `resumeUserInput` / `getOpenConfusion` | next turn's input / `pendingRequests` |
| `postUserInput` | `thread.steer` |
| run state and history | `thread.status`, `thread.items`, `TurnResult` |
| evidence, traces and raw event log | `runtime.diagnostics` |
| protocol request methods | removed; expose a host tool instead |
| `modelClient`, `toolProvider(s)`, `systemPrompt`, `skill` | `model`, `tools`, `instructions`, `skills` |
| `loopSettings` | `settings(LoopSettings(...))` |
| `loopBudget`, `contextWindow`, `modelRetry` | `limits(LoopLimits(...))` |
| `imageInput`, deferred tool flags, `runContextAsSystem`, `lessons`, native tool flags | corresponding `LoopSettings` fields |
| hook/model/tool/session concurrency setters | `LoopSettings.hookTimeoutMillis` / `RuntimeConcurrency` |
| separate durable conversation setup | `storage(directory)`; Android configures it automatically |
| `profile`, `plugin`, `nowEpochMillis` | removed from host API; internal composition/test facilities |
| workspace, hook, context provider, observer, history, subagents, search, policy and evidence configuration | retained under their existing names; advanced options stay experimental |
| Android `DurableSessionEventLogPlugin.appPrivate` | `appPrivateSessionEventLog(context)` |


Fixes for Claude and OpenAI hosts, nothing in Chinese or about a car unless the host is, and an API cleaned up for use
as a public library. **Breaking** in behaviour where noted, for hosts that use what is removed or hidden below, and for
binaries compiled against the old constructors of `AnthropicModelClient`, `OpenAiModelClient`, `A2aServerConfig`,
`AndroidUseOptions`, `SessionHistory`, the kernel's loop classes `NativeToolLoop`, `NativeRoundRunner`,
`NativeToolUseController` and `ToolCallFailureBreaker`, and its prompt pieces `BootstrapPromptProvider.build`,
`ContextBootstrapPromptResolver`, `SessionHistoryStore` and `RuntimeContextHooks.collectContextSystemSuffix`, and
`McpHttpClient` (Kotlin source that names its arguments is unchanged; new parameters come with defaults).

- **Test seams and internal diagnostics are not API.** These are marked `@InternalAgentRuntimeApi`: Kotlin callers
  need an opt-in (Java and existing binaries are unaffected), the API dumps leave them out, and they may change in any
  release.
  - `SessionRunScheduler`'s `beforeClaimGrantedForTest`, `beforeNonCancellableMutexForTest`, `afterAttachJobForTest`,
    `holdMutexForTest` and `snapshot()`, with `SessionRunSchedulerSnapshot`.
  - The cache counters of `ToolProviderRegistry` and `ProviderConcurrencyLimiter`, and `ToolProviderRegistry`'s
    companion with `clearEnvironmentProviderCatalogCacheForTests()`.
  - `RuntimeVfsPerformanceCounters`. Its counter names, the `RUNTIME_COUNTER_*` constants, stay public, since the
    per-run deltas are recorded under them.
- **The probe emitter hub is gone** (`RuntimeProbeEmitterHub`, `RuntimeProbeStageEmitter`, `ProbeEmitterRegistration`).
  The runtime never installed an emitter, so `emitterFor` always returned null. Probe evidence
  (`runtimeEvidence(RuntimeEvidenceConfig(…))`) is unchanged.
- **Java hosts write only what they change.** Interface methods with a body now compile to JVM default methods, so a
  Java `ModelClient` implements only `complete`, and a Java `RuntimeHook` only the callbacks it uses. The `DefaultImpls`
  classes stay beside them, so Kotlin binaries compiled against earlier releases keep working.
- **`agent-a2a` is a plain JVM library** (a jar, not an Android library), so a server or desktop host can use the A2A
  client and server too. Its API is unchanged. An Android app that uses it without `agent-a2a-client-official` now
  declares the `INTERNET` permission itself, which the library's manifest used to add.
- **A2A on Android 6 (API 23).** The direct and official A2A clients' failures were made with a constructor Android only
  has from API 24, and request headers were copied with `Map.forEach` (API 24 too): on Android 6 a refused endpoint
  crashed the host instead of returning a failure, and requests failed without core library desugaring.
- **API docs for the JVM artifacts.** The javadoc jars of `agent-api` and `agent-runtime`
  were empty; they now hold the API docs, like the other artifacts'.
- **The answer goes out as written** (behaviour change). The agent's words to the user went through the trace
  redactor, which turned long numbers (2^40 is 1099511627776, an order number) into `[REDACTED_PHONE]` and hid
  coordinates and key-shaped text. Its text (`AgentEvent.TextDelta`), a completed run's `RuntimeResult.response`, its
  questions (the waiting run's blocker message, `getOpenConfusion`, `AgentEvent.UserInteraction` cards) now go out
  exactly as the model wrote them. Thinking, tool events, summaries and the reports of runs that did not complete are
  filtered as before; a host that wants the answer filtered applies a redactor itself, on the whole text or the result
  (`DefaultTraceRedactor.redact(…)`).
- **A question that looks like a key no longer ends the run.** The durable event log refuses text that starts like a
  credential (`sk-`, `Bearer `), and a question card (`ask_async`) whose title or option did so failed the run: it was
  the one record not left out of the log when refused. It now is, and the card still reaches the host. Likewise a
  user's reply that starts like a key (`postUserInput`) is now accepted and reaches the model, left out of the log,
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
- **Hosts choose what is hidden.** Every long run of digits (ten characters or more of digits, spaces and dashes) in a
  run's public results, events and summaries became `[REDACTED_PHONE]`, an order or tracking number included, with no
  way out: the builder's setter was internal. `AgentRuntimeBuilder.traceRedactor` (experimental) is now open, and
  `StandardTraceRedactor(phones = false, locations = false)` keeps phone numbers or coordinates. Secrets are hidden
  after any host's redactor. `TraceRedactor` is a `fun interface`, and `DefaultTraceRedactor` is unchanged.

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
