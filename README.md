# Android Agent Runtime

[![CI](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/cc.noharry/agent-runtime)](https://central.sonatype.com/namespace/cc.noharry)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/API-23%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions)

**English** | [中文](README.zh-CN.md)

**An embeddable agent runtime (an agent harness) for Android and the JVM.** It runs a full agent loop inside your
app's process: a sandboxed workspace the agent reads and writes, tools from your code and from MCP servers, skills
and subagents, hooks and policy around every tool call, and durable sessions. Bring any OpenAI- or
Anthropic-compatible model.

> **Status: 0.x, under active development.** The public API is not frozen and may change between minor releases;
> breaking changes are listed in the [changelog](CHANGELOG.md).

## What you get

- **The agent loop, on the device.** Native tool calling with streaming, thinking, prompt caching and server-side web
  search where the provider has them; retries, budgets and conversation compaction that summarizes old turns.
- **A sandboxed workspace.** A virtual file system the agent works in; mount app directories, assets, in-memory
  sources or a folder the user picks (SAF). Nothing outside what you mount is reachable.
- **Tools.** Plain Kotlin functions (`functionTools`), any `ToolProvider`, and remote MCP servers over Streamable HTTP
  (`McpHttpClient`, built in). With many tools the agent searches for the right ones instead of seeing them all.
- **Skills, AGENTS.md and subagents.** Skills load on demand; the agent can delegate to child agents and wait for them.
- **Control.** Hooks before and after tool calls, a policy engine, confirmation for network and side-effecting
  actions, and a per-provider concurrency limit.
- **Durable sessions.** An append-only event log per session, resumable runs, SQLite session storage on Android.
- **Optional modules.** Operate other apps' screens through accessibility (Android Use), sandboxed JavaScript that calls
  tools, calendar, notification and media tools, and A2A client and server.

## Install

```kotlin
dependencies {
    implementation(platform("cc.noharry:agent-bom:0.3.0"))
    implementation("cc.noharry:agent-runtime-android") // an Android app
    // implementation("cc.noharry:agent-runtime")      // the JVM: a server, a desktop tool, tests
}
```

Kotlin 2.0 or later (the artifacts are built at language level 2.1), JDK 17, Android `minSdk` 23. The workspace and
the event log use `java.nio.file`, which Android has from API 26: below that, enable core library desugaring with
`com.android.tools:desugar_jdk_libs_nio` (as `samples/quickstart-android` does).

## Quickstart (Android)

```kotlin
val tools = functionTools("device") {
    tool("device_info", "The phone or tablet this app runs on.", readOnly = true) {
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }
}

val runtime = AndroidAgentRuntime.builder(context) // workspace, SQLite sessions, event log, history
    .modelClient(OpenAiModelClient({ apiKey }, model = "gpt-4o-mini", stream = true))
    .toolProvider(tools)
    .build()

scope.launch {
    runtime.run(AgentRequest(requestId = "r1", sessionId = "chat", input = "What device am I on?"))
        .collect { event -> if (event is AgentEvent.TextDelta) answer.append(event.text) }
}
```

Keep API keys off the device in a real app: point `baseUrl` at your own backend.

## Quickstart (JVM, with an MCP server)

```kotlin
val deepWiki = McpToolProvider(
    McpHttpClient("https://mcp.deepwiki.com/mcp", headers = { emptyMap() }, http = OkHttpHttpTransport(),
        clientName = "my-app", clientVersion = "1.0"),
    serverId = "deepwiki",
)

val runtime = AgentRuntimeBuilder.create()
    // Any OpenAI-compatible endpoint: pass its baseUrl. AnthropicModelClient speaks the Messages API.
    .modelClient(OpenAiModelClient({ System.getenv("API_KEY") }, model = "gpt-4o-mini", stream = true))
    .toolProvider(deepWiki)
    .workspaceRoot(File("agent-workspace"))
    .build()
```

The [samples](samples) run as they are: `AGENT_API_KEY=… ./gradlew :samples:quickstart-jvm:run --args="…"`, and
`samples/quickstart-android` is a one-screen app.

## Modules

| Artifact | What it is |
|---|---|
| `agent-runtime` | The runtime and host facade (plain JVM): `AgentRuntimeBuilder`, OpenAI- and Anthropic-compatible model clients, MCP, `functionTools`, skills, subagents, sessions and memory, web search and fetch, hooks and policy, and the workspace's virtual file system |
| `agent-runtime-android` | The Android layer: `AndroidAgentRuntime.builder(context)`, SQLite sessions, the app-private event log, and mounting a folder the user picks through the Storage Access Framework |
| `agent-kernel-api` | Android-neutral contracts: model, tool, plugin, capability, workspace, completion, event log, lifecycle |
| `agent-kernel-core` | The agent loop (`loop`), tool registry and search (`tools`), prompt assembly and compaction (`context`), run state (`state`), plugins and `KernelRuntimeBuilder` (`plugin`), memory and tracing |
| `agent-exec-js` | Optional: `exec_js`, sandboxed JavaScript (QuickJS through Zipline) that can call tools |
| `agent-device-capabilities` | Optional: calendar, notification and media tools |
| `agent-android-use` | Optional: observe and operate the screen through an accessibility service |
| `agent-a2a` | Optional: delegate tasks to remote A2A agents over a direct JSON-RPC/SSE transport, and expose the agent as a local A2A endpoint |
| `agent-a2a-client-official` | Optional: the same client over the official A2A Java SDK |
| `agent-bom` | One version for all of the above |

Three ways in: an app depends on `agent-runtime-android`; a server, desktop tool or test on `agent-runtime`; a runtime
of your own builds on `agent-kernel-api` and `agent-kernel-core` (`samples/minimal` is the smallest one). Optional
modules depend at most on the kernel API.

## Building from source

To try changes not yet released, clone this repository next to your project and let Gradle build it from source; the
same coordinates resolve to it:

```kotlin
// settings.gradle.kts of your project
includeBuild("../android-agent-runtime")
```

## Showcase

[Bothy](https://github.com/NoHarry/bothy) is a tablet-first demo built on this runtime: a floating voice assistant,
public MCP servers, plans that watch a server's state, generative UI, and a simulated car head unit to drive.

## Building

JDK 17 and the Android SDK. `./gradlew ciCheck` runs what CI runs: the source rules, the API compatibility check, every
unit test and the sample builds. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). Third-party code and native libraries keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
