# Changelog

All notable changes are listed here. The project is 0.x: a minor release may break the API, and every break is
called out.

## 0.1.0 (unreleased)

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
