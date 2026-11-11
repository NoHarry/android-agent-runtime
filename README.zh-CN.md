# Android Agent Runtime

[![CI](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/cc.noharry/agent-runtime)](https://central.sonatype.com/namespace/cc.noharry)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/API-23%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions)

[English](README.md) | **中文**

**可嵌入的 agent 运行时（agent harness），面向 Android 和 JVM。** 它在你的 App 进程里跑一整套 agent 循环：agent 能读写的沙箱工作区、来自你自己代码和 MCP 服务的工具、skill 和子 agent、包在每次工具调用外面的 hook 与策略，以及可持久化的会话。模型用任何 OpenAI 或 Anthropic 兼容的接口都行。

> **0.5.0 尚未发布。** 本分支已使用新的 Thread / Turn / Item API，破坏性变更和迁移对照见 [CHANGELOG](CHANGELOG.md)。

## 有什么

- **在设备上跑的 agent 循环。** 原生工具调用，支持流式输出、思考过程、prompt 缓存和服务端联网搜索（看模型厂商是否提供）；带重试、预算控制，以及把旧对话摘要压缩的上下文压缩。
- **沙箱工作区。** agent 在一个虚拟文件系统里干活；宿主可以挂载 App 目录、assets、内存数据，或者用户选的文件夹（SAF）。没挂载的东西它碰不到。
- **工具。** 普通 Kotlin 函数（`functionTools`）、任意 `ToolProvider`，以及走 Streamable HTTP 的远程 MCP 服务（内置 `McpHttpClient`）。工具多的时候，agent 会先搜索再用，不必一次看全部。
- **Skill、AGENTS.md 和子 agent。** skill 按需加载；agent 可以把任务交给子 agent 并等待结果。
- **可控。** 工具调用前后的 hook、策略引擎、联网和有副作用操作的确认，以及按 provider 限制并发。
- **持久化会话。** 每个会话一份只追加的事件日志，运行可恢复，Android 上用 SQLite 存会话。
- **可选模块。** 通过无障碍服务操作别的 App（Android Use）、能调用工具的沙箱 JavaScript、日历和通知工具，以及 A2A 客户端和服务端。

> 本分支面向尚未发布的 0.5.0，新 API 使用 Thread / Turn / Item。请源码接入，或先运行
> `./gradlew publishToMavenLocal` 并在宿主仓库配置 `mavenLocal()`。Maven Central 当前仍是 0.4.0，
> 与下方示例的 API 不同；迁移对照见 [CHANGELOG](CHANGELOG.md)。

## 安装

```kotlin
dependencies {
    implementation(platform("cc.noharry:agent-bom:0.5.0-SNAPSHOT"))
    implementation("cc.noharry:agent-runtime-android") // Android App
    // implementation("cc.noharry:agent-runtime")      // JVM：服务端、桌面工具、测试
}
```

需要 Kotlin 2.0 及以上（构件按 2.1 语言级别编译）、JDK 17，Android `minSdk` 23。工作区和事件日志用到了 `java.nio.file`，Android 从 API 26 起才有：minSdk 低于 26 时，请用 `com.android.tools:desugar_jdk_libs_nio` 2.1.5 及以上开启 core library desugaring（`samples/quickstart-android` 就是这样配的）。

## 快速开始（Android）

```kotlin
val tools = functionTools("device") {
    tool("device_info", "The phone or tablet this app runs on.", readOnly = true) {
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }
}

val runtime = AndroidAgentRuntime.builder(context) // 工作区、SQLite 会话、事件日志、对话历史
    .model(OpenAiModelClient({ apiKey }, model = "gpt-4o-mini", stream = true))
    .tools(tools)
    .build()

scope.launch {
    runtime.startThread().runStreamed("我用的是什么设备？")
        .collect { event -> if (event is ThreadEvent.ItemDelta) answer.append(event.text) }
}
```

`thread.run(text)` 返回完整的 `TurnResult`，`runStreamed` 提供 8 种事件。每个条目有稳定 id，
`ItemCompleted` 带完整内容。批准条目在同一事件流里等待 `approve()` 或 `deny()`；提问以
`WAITING_FOR_ANSWER` 结束当轮，下一轮输入就是回答。运行中可用 `thread.steer(text)` 插话，
用 `thread.interrupt()` 中断。JVM 配置 `.storage(directory)` 后可在重启后恢复模型历史和
`thread.items()`；Android 默认使用 App 私有目录。现场工具参数和结果保留原文，持久化快照遵循配置的过滤器。

正式产品里别把 API key 放在设备上：把 `baseUrl` 指向你自己的后端。

## 快速开始（JVM，接一个 MCP 服务）

```kotlin
val deepWiki = McpToolProvider(
    McpHttpClient("https://mcp.deepwiki.com/mcp", headers = { emptyMap() }, http = OkHttpHttpTransport(),
        clientName = "my-app", clientVersion = "1.0"),
    serverId = "deepwiki",
)

val runtime = AgentRuntime.builder()
    // 任何 OpenAI 兼容接口都行，传它的 baseUrl；AnthropicModelClient 走 Messages API。
    .model(OpenAiModelClient({ System.getenv("API_KEY") }, model = "gpt-4o-mini", stream = true))
    .tools(deepWiki)
    .workspaceRoot(File("agent-workspace"))
    .build()
```

[samples](samples) 里的示例拿来就能跑：`AGENT_API_KEY=… ./gradlew :samples:quickstart-jvm:run --args="…"`；`samples/quickstart-android` 是一个单屏 App。`./gradlew :samples:extensions:run` 不需要 key 也不联网：自己的模型（用一个按脚本走的模型代替端侧模型）、来自你代码的工具，以及一个拒绝某次调用的 hook。

## 模块

| 构件 | 是什么 |
|---|---|
| `agent-runtime` | 运行时和宿主门面（纯 JVM）：`AgentRuntimeBuilder`、OpenAI / Anthropic 兼容的模型客户端、MCP、`functionTools`、skill、子 agent、会话与记忆、联网搜索与读网页、hook 与策略，以及工作区背后的虚拟文件系统 |
| `agent-runtime-android` | Android 层：`AndroidAgentRuntime.builder(context)`、SQLite 会话、App 私有的事件日志，以及把用户通过 SAF 选的文件夹挂进工作区 |
| `agent-api` | 与 Android 无关的 Thread / Turn / Item 契约，以及模型、工具、hook 和事件日志扩展接口 |
| `agent-exec-js` | 可选：`exec_js`，能调用工具的沙箱 JavaScript（Zipline 里的 QuickJS）；app server 的脚本（插件包里用 JavaScript 写的工具）也要它 |
| `agent-device-capabilities` | 可选：日历、通知和媒体控制工具 |
| `agent-android-use` | 可选：通过无障碍服务看屏幕、操作屏幕 |
| `agent-a2a` | 可选（纯 JVM）：通过直连的 JSON-RPC/SSE 传输把任务交给远程 A2A agent，并把 agent 本身开放成本地 A2A 端点 |
| `agent-a2a-client-official` | 可选：同一个客户端，改用官方 A2A Java SDK 做传输 |
| `agent-acp` | 可选（纯 JVM）：通过 Agent Client Protocol（ACP）把 agent 的会话开放给编辑器等 ACP 客户端，走 stdio 或任意一对流 |
| `agent-bom` | 对齐以上所有模块和下面 app server 两个构件的版本 |

**App server**（实验性）把一个 Android App 做成 agent：一个运行时放在线程、轮次和条目的 JSON-RPC 协议后面，每个前端都是它的
客户端，其余功能经插件内核接进来。给宿主的指南是 [agent-app-server/README.md](agent-app-server/README.md)（英文），
`samples/agent-app-sample*` 是四个跑在它上面的 App。

| 构件 | 是什么 |
|---|---|
| `agent-app-protocol` | 协议（纯 JVM）：线程、轮次和条目上的请求、通知和审批，前端渲染的状态，以及它的客户端（`ServerClient`） |
| `agent-app-server` | 服务端（Android）：协议后面的会话、模型、MCP、skill、子 agent 和用量，插件内核（`AppServerPlugin`、`PluginHost`），以及用户安装的插件包；宿主用 `AppServerConfig` 装上它 |

插件是宿主自己的代码（`AppServerPlugin`），一个都不发布。第一方插件（卡片、DeepSeek 的额外功能、操作屏幕、设备工具、画页面、
记忆、画图、计划、A2A）是 [Bothy](https://github.com/NoHarry/bothy) App 的源码模块，编进那个 App，不作为 Maven 构件发布，改它们不用给本仓发版；
它们的源码随 Bothy 的下一个版本公开。
核心不引用任何插件的包，也不带只有插件才用的运行时模块（`agent-a2a`、`agent-a2a-client-official`、`agent-android-use`、
`agent-device-capabilities`；`ciCheck` 里的 `checkAppServerLayering`）。

Android App 依赖 `agent-runtime-android`；服务端、桌面工具或测试依赖 `agent-runtime`。可选模块最多依赖 `agent-api`。agent 循环已收进 runtime 内部，`samples/minimal` 展示只配置模型的最小宿主。

## API 稳定性

从 0.5.0 起，Thread / Turn / Item 主线 API 和 `cc.noharry.agent.spi` 的主要扩展接口进入稳定承诺。高级配置和诊断仍标为实验性，迁移说明记录在 [CHANGELOG](CHANGELOG.md) 里。

- 标了 `@ExperimentalAgentRuntimeApi` 或 `@ExperimentalA2aClientApi` 的声明最可能变化，需要 opt-in（不加时，运行时的会编译报错，A2A 客户端的只是警告）。
- App server 的构件（`agent-app-protocol`、`agent-app-server`）整体是实验性的；入口（`ServerClient`、`AppServer`、`AppServerConfig`、`AppServerPlugin`）标了 `@ExperimentalAppServerApi`，不加 opt-in 时是警告。
- 标了 `@InternalAgentRuntimeApi` 的声明不算 API：只给运行时自己的模块和测试用，任何版本都可能改或删。
- 每个模块的公开 API 都记录在它的 `api/*.api` 里，CI 会和代码比对，API 的任何变化都不会悄悄发生。

安全问题报告见 [SECURITY.md](SECURITY.md)。

## 从源码构建

想用还没发布的改动，把本仓库 clone 到你的工程旁边，让 Gradle 从源码构建，同样的坐标会自动指向它：

```kotlin
// 你工程的 settings.gradle.kts
includeBuild("../android-agent-runtime")
```

## 示例应用

[Bothy](https://github.com/NoHarry/bothy) 是基于本运行时做的平板优先演示：悬浮语音助手、公开 MCP 服务、监听服务器状态的计划、生成式界面，还有一个可以语音操控的模拟车机。

## 构建

需要 JDK 17 和 Android SDK。`./gradlew ciCheck` 跑的就是 CI 跑的内容：源码规则、API 兼容检查、Android API 级别检查和 lint、全部单元测试和示例构建。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE)。第三方代码和 native 库沿用各自的许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
