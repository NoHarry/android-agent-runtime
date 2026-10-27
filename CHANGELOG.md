# Changelog

All notable changes are listed here. The project is 0.x: a minor release may break the API, and every break is
called out.

## Unreleased (0.4.0)

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
