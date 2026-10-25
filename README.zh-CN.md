# Android Agent Runtime

[![CI](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/NoHarry/android-agent-runtime/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/cc.noharry/agent-runtime)](https://central.sonatype.com/namespace/cc.noharry)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/API-23%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions)

[English](README.md) | **中文**

**可嵌入的 agent 运行时（agent harness），面向 Android 和 JVM。** 它在你的 App 进程里跑一整套 agent 循环：agent 能读写的沙箱工作区、来自你自己代码和 MCP 服务的工具、skill 和子 agent、包在每次工具调用外面的 hook 与策略，以及可持久化的会话。模型用任何 OpenAI 或 Anthropic 兼容的接口都行。

> **状态：0.x，持续开发中。** 公开 API 还没冻结，次版本之间可能变化，破坏性变更会写在 [CHANGELOG](CHANGELOG.md) 里。

## 有什么

- **在设备上跑的 agent 循环。** 原生工具调用，支持流式输出、思考过程、prompt 缓存和服务端联网搜索（看模型厂商是否提供）；带重试、预算控制，以及把旧对话摘要压缩的上下文压缩。
- **沙箱工作区。** agent 在一个虚拟文件系统里干活；宿主可以挂载 App 目录、assets、内存数据，或者用户选的文件夹（SAF）。没挂载的东西它碰不到。
- **工具。** 普通 Kotlin 函数（`functionTools`）、任意 `ToolProvider`，以及走 Streamable HTTP 的远程 MCP 服务（内置 `McpHttpClient`）。工具多的时候，agent 会先搜索再用，不必一次看全部。
- **Skill、AGENTS.md 和子 agent。** skill 按需加载；agent 可以把任务交给子 agent 并等待结果。
- **可控。** 工具调用前后的 hook、策略引擎、联网和有副作用操作的确认，以及按 provider 限制并发。
- **持久化会话。** 每个会话一份只追加的事件日志，运行可恢复，Android 上用 SQLite 存会话。
- **可选模块。** 通过无障碍服务操作别的 App（Android Use）、带本地唤醒词的普通话语音会话、能调用工具的沙箱 JavaScript、日历和通知工具，以及 A2A 客户端和服务端。

## 安装

```kotlin
dependencies {
    implementation(platform("cc.noharry:agent-bom:0.1.0"))
    implementation("cc.noharry:agent-runtime-android") // Android App
    // implementation("cc.noharry:agent-runtime")      // JVM：服务端、桌面工具、测试
}
```

需要 Kotlin 2.0 及以上（构件按 2.1 语言级别编译）、JDK 17，Android `minSdk` 23。工作区和事件日志用到了 `java.nio.file`，Android 从 API 26 起才有：minSdk 低于 26 时，请用 `com.android.tools:desugar_jdk_libs_nio` 开启 core library desugaring（`samples/quickstart-android` 就是这样配的）。

## 快速开始（Android）

```kotlin
val tools = functionTools("device") {
    tool("device_info", "The phone or tablet this app runs on.", readOnly = true) {
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }
}

val runtime = AndroidAgentRuntime.builder(context) // 工作区、SQLite 会话、事件日志、对话历史
    .modelClient(OpenAiModelClient({ apiKey }, model = "gpt-4o-mini", stream = true))
    .toolProvider(tools)
    .build()

scope.launch {
    runtime.run(AgentRequest(requestId = "r1", sessionId = "chat", input = "我用的是什么设备？"))
        .collect { event -> if (event is AgentEvent.TextDelta) answer.append(event.text) }
}
```

正式产品里别把 API key 放在设备上：把 `baseUrl` 指向你自己的后端。

## 快速开始（JVM，接一个 MCP 服务）

```kotlin
val deepWiki = McpToolProvider(
    McpHttpClient("https://mcp.deepwiki.com/mcp", headers = { emptyMap() }, http = OkHttpHttpTransport(),
        clientName = "my-app", clientVersion = "1.0"),
    serverId = "deepwiki",
)

val runtime = AgentRuntimeBuilder.create()
    // 任何 OpenAI 兼容接口都行，传它的 baseUrl；AnthropicModelClient 走 Messages API。
    .modelClient(OpenAiModelClient({ System.getenv("API_KEY") }, model = "gpt-4o-mini", stream = true))
    .toolProvider(deepWiki)
    .workspaceRoot(File("agent-workspace"))
    .build()
```

[samples](samples) 里的示例拿来就能跑：`AGENT_API_KEY=… ./gradlew :samples:quickstart-jvm:run --args="…"`；`samples/quickstart-android` 是一个单屏 App。

## 模块

| 构件 | 是什么 |
|---|---|
| `agent-runtime` | 运行时和宿主门面（纯 JVM）：`AgentRuntimeBuilder`、OpenAI / Anthropic 兼容的模型客户端、MCP、`functionTools`、skill、子 agent、会话与记忆、联网搜索与读网页、hook 与策略 |
| `agent-runtime-android` | Android 层：`AndroidAgentRuntime.builder(context)`、SQLite 会话、App 私有的事件日志 |
| `agent-kernel-api` | 与 Android 无关的契约：模型、工具、插件、能力、工作区、完成判定、事件日志、生命周期 |
| `agent-kernel-core` | agent 循环：调度器、原生工具循环、profile 编译器、`KernelRuntimeBuilder` |
| `agent-minimal` | 直接搭在内核上的最小 agent，自己写运行时的起点 |
| `agent-vfs` | 工作区背后的虚拟文件系统 |
| `agent-vfs-saf` | 可选：把用户通过 SAF 选的文件夹挂进工作区 |
| `agent-exec-js` | 可选：`exec_js`，能调用工具的沙箱 JavaScript（Zipline 里的 QuickJS） |
| `agent-device-capabilities` | 可选：日历和通知工具 |
| `agent-android-use` | 可选：通过无障碍服务看屏幕、操作屏幕 |
| `agent-voice` | 可选：普通话语音会话，本地唤醒词和识别（sherpa-onnx）或云端识别 |
| `agent-a2a-client`、`-direct`、`-official` | 可选：把任务交给远程 A2A agent（两种可互换的传输） |
| `agent-a2a-server` | 可选：把 agent 本身开放成本地 A2A 端点 |
| `agent-bom` | 对齐以上所有模块的版本 |

三种用法：App 依赖 `agent-runtime-android`；服务端、桌面工具或测试依赖 `agent-runtime`；自己重建运行时就基于 `agent-kernel-api` 和 `agent-kernel-core`。可选模块最多依赖内核 API。

## 从源码构建

想用还没发布的改动，把本仓库 clone 到你的工程旁边，让 Gradle 从源码构建，同样的坐标会自动指向它：

```kotlin
// 你工程的 settings.gradle.kts
includeBuild("../android-agent-runtime")
```

## 示例应用

[Bothy](https://github.com/NoHarry/bothy) 是基于本运行时做的平板优先演示：悬浮语音助手、公开 MCP 服务、监听服务器状态的计划、生成式界面，还有一个可以语音操控的模拟车机。

## 构建

需要 JDK 17 和 Android SDK。`./gradlew ciCheck` 跑的就是 CI 跑的内容：源码规则、API 兼容检查、全部单元测试和示例构建。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE)。第三方代码和 native 库沿用各自的许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
