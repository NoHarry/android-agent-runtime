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
  - `finish` (or a text-only answer) that only announces a lookup in Chinese, such as 「我将为你查询当前的空调状态。」, is
    sent back for a real answer once, like its English forms.
- **Binary break:** `ModelMessage`, `ModelTool` and `RoundOutcome.Failed` gained parameters.

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
