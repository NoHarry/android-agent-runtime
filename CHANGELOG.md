# Changelog

All notable changes are listed here. The project is 0.x: a minor release may break the API, and every break is
called out.

## Unreleased

- `agent-voice`: the wake word listens with sherpa-onnx's Chinese–English keyword spotter
  (`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`), so a wake phrase may be English, written in the model's phones
  (`WakePhrase("OW1 K EY1 B AA1 TH IY0", "OK_Bothy")`). **Breaking:** an app that ships the wake model ships this one
  in its assets in place of the WenetSpeech one (`WAKE_MODEL_ASSET_DIR`); the Chinese phrases' pinyin tokens are the
  same. `WakePhrase.threshold` and `WakePhrase.boost` set one phrase's threshold and boost apart from the spotter's,
  and a phrase must be reported without spaces.

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
