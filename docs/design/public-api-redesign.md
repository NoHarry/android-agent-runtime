# 对外 API 重设计（0.5.0）

> 状态：owner 于 2026-11-06 确认全部按 9.3 推荐实施；9.3 覆盖 9.2 的模块、包名推荐。Maven Central 发布仍需单独授权。
> 已定：0.5.0 直接用新 API 替换旧 API，不留过渡版本；0.5.0 等新 API 做完再发。kernel-core 并进 agent-runtime（见 9.1）。
> 基线：运行时 main `d2f1c49`（0.5.0-SNAPSHOT）；Bothy 指 `bothy-server` 仓的 main。文中数字都在基线上量过，代码改了要重量。
> 接手须知：先看第 9 节「决定」，按已确认的 9.3 和第 10 节顺序实施，每步过了验证再走下一步。

## 1. 背景与目标

目标是把运行时的对外 API 收成 Codex SDK 那样小而稳的一层：宿主写十几行就能跑一个会话，其余部分退到扩展接口或内部实现。

**为什么要改：**

- **对外面太大。** 9 个模块的 API dump 约 9,400 行、约 770 个公开类，光 kernel-core 就有 322 个。连 `NativeToolLoop`、`NativeRoundRunner` 这种循环内部的类都公开了。作为对照，Codex 的 TypeScript SDK 源码一共约 1,000 行，导出约 25 个类型。
- **事件不成体系。** `AgentEvent` 有 23 种。文字增量不带消息 id。工具事件的参数和结果是 `{"redacted":true}`，宿主拼不出一次完整的工具调用。Bothy 只好在 `agent-app-server` 里自己再搭一层 Thread / Turn / Item 给界面用。
- **内部流程的概念露在外面。** Evidence、Workpad、Handoff、OpenConfusion、ProtocolRequest、Blocker 都是公开的。
- **暂停有三套。** 等确认、等用户回复、等协议请求，各有各的恢复方法。
- **稳定性说不清。** 有 262 处 `@ExperimentalAgentRuntimeApi`，没有一块明确是稳定的。

**目标：**

1. 宿主用的主线照 Codex 来：会话（Thread）→ 轮次（Turn）→ 条目（Item）。流式事件只有开始、增量、更新、完成、失败几种。
2. 暂停统一：都以条目的形式出现在流里，回应方式写死在类型上（见 4.6）。
3. 分三层：主线 API（稳定）、扩展接口（`ModelClient`、`ToolProvider`、hook 等，稳定）、内部实现（不进 API dump）。
4. 输入能带图片和附件。
5. 现有能力一样不少：MCP、skill、子 agent、A2A、记忆、压缩、事件日志、断点恢复。

**不做：**

- 不改 agent 循环本身的行为：提示词、工具搜索、压缩、熔断都不动。
- 不留兼容层。旧 API 在 0.5.0 里直接删掉，迁移方法写进 CHANGELOG（见第 7 节）。
- 不搬 Codex 编码 agent 专属的东西，比如命令沙箱、git、结构化输出 schema。

## 2. 现状盘点

### 2.1 公开面

公开面主要集中在 kernel-api 和 kernel-core，宿主天天用到的只是其中很小一部分。

| 模块 | API dump 行数 | 公开类 |
|---|---:|---:|
| agent-kernel-core | 3,882 | 322 |
| agent-kernel-api | 3,097 | 230 |
| agent-runtime | 1,335 | 119 |
| agent-a2a | 792 | 69 |
| agent-android-use | 149 | 12 |
| agent-device-capabilities | 63 | 5 |
| agent-a2a-client-official | 40 | 9 |
| agent-runtime-android | 27 | 4 |
| agent-exec-js | 10 | 1 |

kernel-core 的公开类型里，宿主经 agent-runtime 的 API 签名碰到的只有 4 个：`LoopSettings`、`ProtocolRules`、`ToolDomainNaming`、`TraceRedactor`。其余都是循环、工具注册、状态存储、上下文拼装的内部件。

`AgentRuntimeBuilder` 有 54 个方法。Codex 的 `ThreadOptions` 约 14 个字段。

### 2.2 `AgentRuntime` 的 21 个方法和 23 种事件

| 用途 | 方法 |
|---|---|
| 跑 | `run`、`close` |
| 确认 | `resumeConfirmation`、`getPendingConfirmation`、`cancelPendingConfirmation` |
| 用户输入 | `resumeUserInput`、`postUserInput`、`getOpenConfusion` |
| 协议请求 | `resolveRuntimeProtocolRequest`、`rejectRuntimeProtocolRequest`、`cancelRuntimeProtocolRequest`、`getPendingProtocolRequests` |
| 运行记录 | `getRunState`、`getRuntimeResult`、`getRuntimeHandoff`、`getRuntimeEvidence`、`getRuntimeTraceEvents` |
| 事件日志 | `querySessionEvents`、`replaySessionEvents`、`subscribeSessionEvents`、`getSessionEventLogHealth` |

`AgentEvent` 的 23 种是：Started、UserInteraction、ThinkingDelta、TextDelta、TokenUsageDelta、ConfirmationRequired、RuntimeBlocked、RuntimeProtocolRequestCreated、RetryAttempted、BudgetWarning、CompactionApplied、EmptyCompletionObserved、SteeringAccepted、NativeToolCallStarted、NativeToolCallFinished、NetworkToolActivity、SearchActivity、ExecOutputDelta、WorkspaceFileChanged、WorkspaceChanged、ResultAvailable、Finished、Failed。

请求是 `AgentRequest(requestId, sessionId, input: String, scene, resumeRunId, modelRef, workspaceId, currentStateKeys, sessionRunConflictPolicy)`。

### 2.3 Bothy 实际用到的

下面是 Bothy 三个模块（agent-app-server、bothy-server、bothy-app）里的调用次数：

- 用到的方法：`run`、`resumeConfirmation`、`getPendingConfirmation`、`getRunState`、`getRuntimeResult`、`getRuntimeHandoff`、`postUserInput`、`getRuntimeEvidence`、`getRuntimeTraceEvents`，以及 4 个事件日志方法。
- 没用到的方法：`cancelPendingConfirmation`、`resumeUserInput`、`getOpenConfusion`，以及 4 个协议请求方法。
- 23 种事件全都处理了（最多的是 `ResultAvailable`，有 7 处）。
- 扩展接口：用 `ModelClient`、`ToolProvider`、`McpClientProvider`、`RuntimeHook`、`ToolPrefetchProvider`、`RuntimeContextProvider`、`SessionEventLog`、`Subagents`、`SessionHistory`、`VfsFileSystem`。不用 `AgentPlugin`、`RuntimeTurnObserver`、`NarrationCheck`、`ServiceAnswerCheck`、`SessionCapabilityFactory`。
- 直接 import kernel-core 的有 8 个文件：agent-app-server 里的 `PromptStats.kt`、`RuntimePrompt.kt`、`ToolTranscriptRedactor.kt`、`ContextUsage.kt`、`OfferToolProvider.kt`、`FixtureModelClient.kt`，bothy-server 里的 `ToolCatalogCheck.kt`、`BothyFixtureModel.kt`。用到的符号包括 `KernelRuntimeBuilder`、`ToolProviderRegistry`、`ToolDomainIndexer`、`McpToolRef`、`mcpQualifiedName`、`MaterializerContext`、`DEFERRED_TOOLS_HEADER`、`RUN_CONTEXT_HEADER`、`validateHostSystemPrompt`、提示词字节上限常量、`SESSION_MODEL_CALL_SOURCE_*`、`DefaultTraceRedactor`。

### 2.4 问题

1. **一件事散在几种事件里。** 一段回复是若干 `TextDelta`，结束时又来 `ResultAvailable`、`Finished` 或 `Failed`。一次工具调用是 Started / Finished 两条，网络和输出又在另外两种事件里。宿主得自己拼。
2. **三套暂停各自恢复。**
   - 确认：`ConfirmationRequired` 加 `resumeConfirmation`。
   - 提问：`RuntimeBlocked(WAITING_USER_INPUT)` 加 `resumeUserInput`。Bothy 没用 `resumeUserInput`，而是在同一会话开一轮新的，自己在 `FollowUpReply.kt` 里给回答套上框。
   - 协议请求：`RuntimeProtocolRequestCreated` 加 `resolveRuntimeProtocolRequest`。目前只有内部测试脚手架 `protocolRequestOnRun`（internal）会产生这类请求。
3. **批准后的后半段不在流里。** `ConfirmationRequired` 发出后，`run()` 的 Flow 就结束了（见 `ConfirmationContextResumeTest`）。`resumeConfirmation` 只回一个 `Accepted`，后半段的文字和工具调用不在任何 Flow 里，宿主只能事后查 `getRuntimeResult`。Bothy 的 `RuntimeConfirmation.kt` 就是这么做的。
4. **请求形状偏底层。** 宿主得自己给 `requestId`、`sessionId`，`input` 只能是字符串。
5. **配置面大。** builder 有 54 个方法，有的放主线，有的只是测试用。

## 3. 设计原则

1. **三层分开。**
   - 主线 API：会话、轮次、条目、事件、待回应请求、输入。进入稳定承诺，以后只加不改。
   - 扩展接口（SPI）：宿主实现、运行时调用的接口，比如 `ModelClient`、`ToolProvider`、`RuntimeHook`、`SessionEventLog`。同样稳定，新方法一律带默认实现（`jvmDefault` ENABLE 保留 `DefaultImpls`，Java 宿主不用改）。
   - 内部：kernel-core 并进 agent-runtime 之后，内部代码用 Kotlin 的 `internal`。跨模块还得公开的少数几处（比如给可选模块用的），用 `@InternalAgentRuntimeApi` 标记，不进 API dump；这个标记和 `nonPublicMarkers` 现在已经有了。
2. **一个流里看见一切。** 一轮的所有东西都是条目（item），每个条目有稳定 id，按开始 → 增量/更新 → 完成走。增量挂在条目 id 上。条目完成时带完整内容，宿主不处理增量也拿得到全文。
3. **暂停就是一个条目。** 批准、提问都以条目出现在流里，用类型说明怎么回应。不再有按种类分开的 resume 方法。
4. **宿主不造 id。** 会话、轮次、条目的 id 由运行时生成。宿主想用自己的会话 id（比如对上自己的存储）可以传入。
5. **Kotlin 优先，Java 能用。** 主线用 `suspend` 和 `Flow`，另外给 Java 宿主一个回调加阻塞的包装（4.7）。
6. **默认值中性。** 已批准：必须指定模型；默认值不带任何具体产品或语言的偏好。

## 4. 新 API 草案

下面的 Kotlin 只示意形状，名字和字段在评审里定。

### 4.1 最短用法

```kotlin
val runtime = AgentRuntime.builder()
    .model(OpenAiModelClient(apiKeyProvider = { key }, model = "gpt-4o-mini", baseUrl = url))
    .tools(clock)
    .build()

val thread = runtime.startThread()
val turn = thread.run("现在几点？")
println(turn.finalResponse)
```

流式：

```kotlin
thread.runStreamed("把客厅灯关了").collect { event ->
    when (event) {
        is ThreadEvent.ItemDelta -> print(event.text)
        is ThreadEvent.ItemStarted -> when (val item = event.item) {
            is ThreadItem.ToolCall -> println("[tool] ${item.tool}")
            is ThreadItem.Approval -> item.approve()          // 或 item.deny("不行")
            else -> Unit
        }
        is ThreadEvent.TurnFailed -> println("[failed] ${event.error.code}")
        else -> Unit
    }
}
```

作为对照，`samples/quickstart-jvm` 现在要自己造 `AgentRequest(requestId, sessionId, input)`，再对 23 种事件做 `when`。

### 4.2 入口和会话

```kotlin
interface AgentRuntime : AutoCloseable {
    fun startThread(options: ThreadOptions = ThreadOptions()): AgentThread
    fun resumeThread(threadId: String, options: ThreadOptions = ThreadOptions()): AgentThread
    suspend fun listThreads(): List<ThreadInfo>
    suspend fun deleteThread(threadId: String)
    val diagnostics: RuntimeDiagnostics          // 调试用，不在稳定承诺里（第 6 节）
    override fun close()
}

data class ThreadOptions(
    val threadId: String? = null,     // 不给就由运行时生成
    val model: ModelRef? = null,      // 覆盖默认模型
    val workspace: String? = null,    // 已注册的工作区 id；不给就是没有工作区
    val scene: String? = null,        // 场景规则（对应 sceneAgentsMd 的键）
    val instructions: String? = null, // 这个会话额外的系统提示
)

data class ThreadInfo(val id: String, val createdAt: Long, val updatedAt: Long, val status: ThreadStatus)
```

- 类型叫 `AgentThread` 不叫 `Thread`：Kotlin 在 JVM 上默认 import `java.lang.*`，叫 `Thread` 会和 `java.lang.Thread` 撞名。
- `resumeThread` 接上的历史来自现在的会话存储。进程重启后模型能看到多少旧历史，要先在平板上验证：Bothy 现在会在内存历史为空时自己补一段 `PreviousExchange` 回顾，说明默认是接不上的。这次不改历史机制，只把行为写进 KDoc。
- `ThreadStatus`：`IDLE`、`RUNNING`、`WAITING_FOR_APPROVAL`、`WAITING_FOR_ANSWER`。它和 Bothy 协议里的运行状态（working、waitingForApproval 等）对得上。

### 4.3 轮次和输入

```kotlin
interface AgentThread {
    val id: String
    suspend fun run(input: TurnInput, options: TurnOptions = TurnOptions()): TurnResult
    fun runStreamed(input: TurnInput, options: TurnOptions = TurnOptions()): Flow<ThreadEvent>
    suspend fun steer(text: String): SteerResult                       // 正在跑时插话，即现在的 postUserInput
    suspend fun interrupt()                                            // 停下当前轮次
    suspend fun status(): ThreadStatus
    suspend fun pendingRequests(): List<PendingRequest>
    suspend fun items(afterTurnId: String? = null): List<ThreadItem>  // 读回历史，界面重画用
}

suspend fun AgentThread.run(text: String): TurnResult = run(TurnInput.text(text))
fun AgentThread.runStreamed(text: String): Flow<ThreadEvent> = runStreamed(TurnInput.text(text))

data class TurnInput(val parts: List<InputPart>, val answering: String? = null) {
    companion object {
        fun text(text: String): TurnInput
        fun of(vararg parts: InputPart): TurnInput
    }
}

sealed interface InputPart {
    data class Text(val text: String) : InputPart
    data class Image(val bytes: ByteArray, val mimeType: String) : InputPart  // 进 ModelMessage.images
    data class Attachment(val ref: SessionAttachmentRef) : InputPart          // 已存入会话的附件
}

data class TurnOptions(
    val turnId: String? = null,                    // 幂等用，比如 A2A 的 message id；不给就生成
    val model: ModelRef? = null,
    val currentStateKeys: Set<String> = emptySet(),
    val onConflict: SessionRunConflictPolicy? = null,
)

data class TurnResult(
    val turnId: String,
    val status: TurnStatus,            // COMPLETED / WAITING_FOR_ANSWER / INTERRUPTED / FAILED
    val finalResponse: String,
    val items: List<ThreadItem>,
    val usage: Usage?,
    val pending: PendingRequest?,      // WAITING_FOR_ANSWER 时是那个提问
    val error: TurnError?,
)
```

- 取消 `runStreamed` 的收集就等于 `interrupt()`，跟 Codex 的 `signal` 一样。
- `TurnInput.answering` 见 4.6 的提问部分。
- 图片能不能发，取决于模型：`imageInput(enabled)` 和模型能力检查都保留。模型不收图时，这一轮以 `TurnFailed(code = IMAGE_INPUT_UNSUPPORTED)` 结束，不静默丢图。

### 4.4 事件

```kotlin
sealed interface ThreadEvent {
    data class TurnStarted(val threadId: String, val turnId: String) : ThreadEvent
    data class ItemStarted(val item: ThreadItem) : ThreadEvent
    data class ItemDelta(val itemId: String, val text: String) : ThreadEvent   // 回复、推理、命令输出的增量
    data class ItemUpdated(val item: ThreadItem) : ThreadEvent
    data class ItemCompleted(val item: ThreadItem) : ThreadEvent
    data class UsageUpdated(val turnId: String, val usage: Usage) : ThreadEvent
    data class TurnCompleted(val result: TurnResult) : ThreadEvent
    data class TurnFailed(val turnId: String, val error: TurnError) : ThreadEvent
}
```

一共 8 种。顺序规则写进 KDoc，并用测试守住：

1. `TurnStarted` 最先，`TurnCompleted` 或 `TurnFailed` 最后，两者只出现一个。
2. 每个条目先有 `ItemStarted`，然后是若干 `ItemDelta` / `ItemUpdated`，最后正好一个 `ItemCompleted`。
3. `ItemCompleted` 带完整内容。
4. 轮次结束前，所有开始了的条目都已经完成（失败的以 `status = FAILED` 完成）。

Codex 的 app-server 按条目种类分了几种增量通知（`item/agentMessage/delta`、`item/reasoning/textDelta`、`item/commandExecution/outputDelta`）。这里合成一个 `ItemDelta`，因为条目种类在 `ItemStarted` 里已经知道了。

### 4.5 条目

```kotlin
sealed interface ThreadItem {
    val id: String
    val turnId: String

    data class UserMessage(…, val input: List<InputPart>, val steer: Boolean = false) : ThreadItem
    data class AgentMessage(…, val text: String) : ThreadItem
    data class Reasoning(…, val text: String) : ThreadItem
    data class ToolCall(
        …,
        val tool: String,
        val source: ToolSource,            // 原生、MCP、函数工具、子 agent、A2A；沿用现有 ToolSource
        val server: String?,               // MCP server id 或子 agent 名
        val arguments: String,
        val output: String?,
        val status: ItemStatus,            // IN_PROGRESS / COMPLETED / FAILED / DECLINED
        val network: NetworkActivity?,     // 原 NetworkToolActivity 的域名、状态码、截断
    ) : ThreadItem
    data class WebSearch(…, val provider: String, val query: String, val resultCount: Int?) : ThreadItem
    data class FileChange(…, val path: String, val added: Int, val removed: Int, val tool: String) : ThreadItem
    data class Notice(…, val kind: NoticeKind, val message: String) : ThreadItem  // RETRY、COMPACTION、BUDGET_LOW、WORKSPACE_CHANGED
    data class Error(…, val code: RuntimeErrorCode, val message: String, val recoverable: Boolean) : ThreadItem
    // 待回应的请求也是条目，见 4.6
}
```

和 Bothy 协议的 `ConversationItem` 基本一一对应（第 8 节有对照）。工具调用的参数和结果给不给原文，见第 9 节第 2 问。

### 4.6 待回应的请求

```kotlin
sealed interface PendingRequest : ThreadItem {
    val expiresAt: Long?
}

data class Approval(
    …, val tool: String, val arguments: String, val domain: String, val reason: String,
) : PendingRequest {
    suspend fun approve(scope: ApprovalScope = ApprovalScope.ONCE): RespondResult   // ONCE / THREAD
    suspend fun deny(reason: String? = null): RespondResult
}

data class Question(…, val text: String, val choices: List<String>, val async: Boolean) : PendingRequest

sealed interface RespondResult {
    data object Accepted : RespondResult
    data object NotFound : RespondResult
    data object Expired : RespondResult
    data object AlreadyAnswered : RespondResult
}
```

**批准当轮等待。** 流里先出现 `ItemStarted(Approval)`，然后这一轮挂起等回应，最长等到 `expiresAt`（现在默认 60 秒）。宿主调 `approve()` 或 `deny()` 之后，流里出现 `ItemCompleted(Approval)`，同一个流接着跑后半段。这一改就解决了 2.4 的第 3 个问题。

- 宿主不在流旁边时（比如回应来自另一个界面），用 `thread.pendingRequests()` 找到请求再回应，效果一样。
- 收集方在等待时取消了流，这一轮就中断，请求按取消处理。
- 这要改内部：现在循环在要确认时直接结束，`pendingToolUseController` 留着暂停的工具调用，`resumeConfirmation` 再重新接入。新做法是循环在原地挂起等结果，重新接入的那套路径随旧 API 一起删掉。

**提问。** 运行时现在给模型两个提问工具，对应两种 `Question`。这是推荐做法，见第 9 节第 1 问。

- `ask`（缺了信息就什么都做不了时用）：流里出现 `ItemCompleted(Question(async = false))`，这一轮以 `TurnCompleted(status = WAITING_FOR_ANSWER, pending = 那个提问)` 结束。下一轮 `run(...)` 的输入就是回答。运行时在内部照现在 `resumeUserInput` 的做法接着同一个 run 跑：模型看到的历史里，`ask` 的工具结果后面紧跟着用户的回答。现在 Bothy 在 `FollowUpReply.kt` 里自己给回答套的框，也挪进 SDK。宿主想明确绑定，就传 `TurnInput(answering = questionId)`。如果下一轮输入其实是新请求（用户不理提问、说了别的），按现有回答框的规则当作新请求处理。
- `ask_async`（一边问一边继续干活，最多 3 题）：流里出现 `Question(async = true)`，这一轮照常继续跑。回答用 `thread.steer(text)` 插进来；这一轮结束时还没回答的问题作废。

这么分的理由：批准是工具执行前的一道闸，时间短，宿主或策略能马上回应；提问要等人，可能很久，甚至永远不回答。让一个轮次一直开着，会长期占住会话的运行权（session lease）。

**协议请求不进主线。** 目前只有内部测试脚手架会产生，推荐整套删掉，见第 9 节第 4 问。

### 4.7 Java 宿主

`suspend` 和 `Flow` 在 Java 里不好用，所以加一个薄包装，放在 agent-runtime：

```kotlin
class BlockingAgentThread(private val thread: AgentThread) {
    fun run(text: String): TurnResult                                   // 阻塞到这一轮结束
    fun runStreamed(text: String, listener: ThreadEventListener): Cancellable
    fun approve(requestId: String): RespondResult
    fun deny(requestId: String, reason: String?): RespondResult
}
```

`JavaHostDefaultsTest` 会扩展，确保 Java 宿主只写自己要改的方法。

### 4.8 配置

builder 按用途分组，常用的只有前几项：

```kotlin
AgentRuntime.builder()
    .model(client)                    // 必填（已批准）
    .tools(provider, …)               // ToolProvider、functionTools { }、McpToolProvider
    .hook(hook, matcher)
    .instructions(systemPrompt)       // 系统提示和 AGENTS.md
    .workspace(ref)                   // 可选；不给就没有文件工具，search_tools 照样能用
    .storage(dir)                     // 会话存储和事件日志；Android 版默认用 app 私有目录
    .skills(…)
    .subagents(…)
    .limits(LoopLimits(…))            // 轮数、工具调用数、超时
    .settings(LoopSettings(…))        // 高级：熔断、压缩、检查
    .build()
```

现有的 54 个方法里，有些归进上面的组（比如 `loopBudget`、`modelRetry`、`contextWindow` 进 `limits` 或 `settings`）。只给测试用的（`nowEpochMillis`、`runtimeEvidence` 一类）改成 internal，或挪到 `diagnostics`。旧方法直接删。每个旧方法归到哪里，实现时列成表放进 CHANGELOG。

## 5. 扩展接口（SPI）

保留，进入稳定承诺：

| 接口 | 调整 |
|---|---|
| `ModelClient` 及 `ModelRequest` / `ModelEvent` / `ModelMessage` / `ModelTool` / `ModelImage` / `ModelRef` | 已批准：`ModelMessage` 加推理字段；`ServerSideSearchCapability` 移出 kernel-api 的 model 包 |
| `ToolProvider` 及 `ToolDescriptor` / `ToolCallContext` / `ToolCallResult` / `ToolSource` | 不变；`functionTools { }` 作为宿主写工具的主路 |
| `McpClientProvider` | 不变 |
| `RuntimeHook` 及 `ToolUse` / `HookContext` / `HookDecision` / `ToolMatcher` | `onRunStarted` / `onRunFinished(RuntimeResult)` 换成 `onTurnStarted` / `onTurnFinished(TurnResult)` |
| `RuntimeContextProvider` | 不变（Bothy 在用） |
| `ToolPrefetchProvider` | 不变（Bothy 有 10 个文件在用） |
| `SessionEventLog` | 不变；宿主读历史改用 `thread.items()`，原始日志走 `diagnostics` |
| `ExecEngine` / `ExecToolBridge` | 不变（agent-exec-js） |
| `Subagents`、`HostSkill`、`SessionHistory`、`VfsFileSystem` | 不变 |

降为「高级」，继续标 `@ExperimentalAgentRuntimeApi`，不进稳定承诺：`NarrationCheck`、`ServiceAnswerCheck`、`RuntimeTurnObserver` 及 `TurnInputObservation` 一族、`LoopSettings` 里的细项。

## 6. 收进内部的范围

| 范围 | 现在 | 处理 |
|---|---|---|
| kernel-core 全部 | 3,882 行，322 类 | 整个并进 agent-runtime，改成 Kotlin `internal`，不再单独发布（9.1）。宿主碰到的 4 个类型（`LoopSettings`、`ProtocolRules`、`ToolDomainNaming`、`TraceRedactor`）留在 agent-runtime 的公开包 |
| reliability 包：`RuntimeEvidence`、`RuntimeHandoff`、`RuntimeWorkpad`、`RuntimeCandidate`、`CompletionItem`、`RuntimeCompletionSummary`、`RuntimeRunEnvelope`、`RuntimeRunSnapshot`、`RuntimeTraceEvent` | 公开 | 移进 `RuntimeDiagnostics`，标 experimental。Bothy 的「轨迹」「调试」页从这里取 |
| `OpenConfusion`、`RuntimeBlocker` | 公开 | 宿主看到的是 `Question` 条目和 `TurnError`；这两个收进内部 |
| 协议请求：`RuntimeProtocolRequest`、4 个方法、`ProtocolRequest*` 结果类型 | 公开，但只有内部测试脚手架会产生 | 推荐整套删掉（第 9 节第 4 问） |
| 插件系统：`AgentPlugin`、`AgentPluginScope`、`AgentPluginDescriptor`、`AgentCapabilities`、`CapabilityKey`、`AgentProfile`、`KernelApiVersion` 一族、`SessionCapabilityFactory` | 公开；在 SDK 里只有 kernel-api、kernel-core、agent-runtime 自己用，可选模块和 Bothy 都没用 | 推荐删掉公开部分，组装逻辑变成 internal（第 9 节第 5 问） |
| `AgentRequest`、`AgentEvent`、`RuntimeResult`、确认和用户输入的请求/结果类型 | 公开 | 0.5.0 删掉或收进内部。`AgentEvent` 可以留作循环内部的事件，由一层转换变成 `ThreadEvent` |

`RuntimeDiagnostics` 的形状：

```kotlin
@ExperimentalAgentRuntimeApi
interface RuntimeDiagnostics {
    suspend fun trace(turnId: String): List<RuntimeTraceEvent>
    suspend fun evidence(evidenceId: String): RuntimeEvidence?
    suspend fun handoff(turnId: String): RuntimeHandoff?
    suspend fun eventLog(threadId: String, turnId: String? = null): List<SessionEvent>
    fun subscribeEventLog(threadId: String): Flow<SessionEvent>
    suspend fun eventLogHealth(threadId: String, turnId: String): SessionEventLogHealth
}
```

目标（实现后要量）：主线 API 的 dump 在 600 行以内，加上扩展接口在 2,000 行以内。现在是 9,400 行。

## 7. 旧 API 到新 API 的对照与迁移

下面两张表也是 CHANGELOG 里迁移说明的底稿。

### 7.1 事件对照

| 旧 `AgentEvent` | 新 |
|---|---|
| Started | `TurnStarted` |
| UserInteraction | `ItemStarted` / `ItemCompleted(Question)` |
| ThinkingDelta | `ItemDelta`，挂在 `Reasoning` 条目上 |
| TextDelta | `ItemDelta`，挂在 `AgentMessage` 条目上 |
| TokenUsageDelta | `UsageUpdated`；最终值在 `TurnResult.usage` |
| ConfirmationRequired | `ItemStarted(Approval)` |
| RuntimeBlocked | 等用户输入时是 `Question` 条目加 `TurnCompleted(WAITING_FOR_ANSWER)`；其他情况是 `TurnFailed` |
| RuntimeProtocolRequestCreated | 不再给宿主 |
| RetryAttempted | `Notice(RETRY)` |
| BudgetWarning | `Notice(BUDGET_LOW)` |
| CompactionApplied | `Notice(COMPACTION)` |
| EmptyCompletionObserved | 不再给宿主，进 `diagnostics.trace` |
| SteeringAccepted | `ItemCompleted(UserMessage(steer = true))` |
| NativeToolCallStarted | `ItemStarted(ToolCall)` |
| NativeToolCallFinished | `ItemCompleted(ToolCall)` |
| NetworkToolActivity | `ItemUpdated(ToolCall)`，填 `network` |
| SearchActivity | `WebSearch` 条目 |
| ExecOutputDelta | `ItemDelta`，挂在 `ToolCall` 条目上 |
| WorkspaceFileChanged | `FileChange` 条目 |
| WorkspaceChanged | `Notice(WORKSPACE_CHANGED)` |
| ResultAvailable、Finished | `TurnCompleted` |
| Failed | `TurnFailed` |

### 7.2 方法对照

| 旧 `AgentRuntime` 方法 | 新 |
|---|---|
| `run(AgentRequest)` | `startThread()` / `resumeThread(id)` 之后 `run` / `runStreamed` |
| `close` | `close` |
| `resumeConfirmation` | `Approval.approve()` / `deny()` |
| `getPendingConfirmation`、`getOpenConfusion` | `thread.pendingRequests()`、`TurnResult.pending` |
| `cancelPendingConfirmation` | `interrupt()`，或让它过期 |
| `resumeUserInput` | 下一轮 `run`（可带 `answering`） |
| `postUserInput` | `thread.steer` |
| 4 个协议请求方法 | 删掉（第 9 节第 4 问） |
| `getRunState` | `thread.status()` |
| `getRuntimeResult` | `run` 的返回值或 `TurnCompleted`；事后用 `thread.items()` |
| `getRuntimeHandoff`、`getRuntimeEvidence`、`getRuntimeTraceEvents` | `diagnostics` |
| 4 个事件日志方法 | 界面用 `thread.items()`；原始日志用 `diagnostics` |

### 7.3 版本安排

0.5.0 一次换完：

- 新 API 替换旧 API，旧的直接删，不留兼容层。
- kernel-core 并进 agent-runtime；reliability 包、协议请求和插件系统按第 6 节和第 9 节处理。
- 0.5.0 推迟到这些做完再发。main 上已有的改动（没有工作区也能用 search_tools、答案不过滤等）一起发。
- 发布前，Bothy 在本地分支上对着 SDK 源码迁完，并在平板上验过（第 8 节）。这样新 API 在发布前已经有一个真实宿主跑过，0.5.0 起主线 API 和扩展接口就能进入稳定承诺，不再标 experimental。
- CHANGELOG 的 0.5.0 一节写清所有破坏性改动，并附第 7.1、7.2 节的迁移对照。

已批准、原定发版后再做的四项（移动 `ServerSideSearchCapability`、给 `ModelMessage` 加推理字段、必须指定模型、默认值改中性）放不放进 0.5.0，见第 9 节第 6 问。

## 8. Bothy 迁移

按规矩，Bothy 要等新 API 发布后才能推送用到它的代码。所以迁移分两段：

1. 发布前，在 Bothy 本地分支上对着 SDK 源码（includeBuild）迁完，跑 Bothy validate 和平板检查，用来在发布前验证新 API。新 API 在这期间要改的，回到 SDK 分支上改。
2. 0.5.0 发布后，把 Bothy 的依赖换成 Maven 上的 0.5.0，评审后推送。

SDK 的改动先放在一个分支上（比如 `api-redesign`），不进 main，等 Bothy 本地迁完再合并。原因是 Bothy 本地开发时，只要 SDK 仓就在旁边，就会对着 SDK 源码编译；SDK main 一删旧 API，Bothy 的 main 在本地就编不过了。分支上的每一步照样评审后推送到这个分支。

| Bothy 位置 | 现在 | 改成 |
|---|---|---|
| `RuntimeRunCollection.kt`、`RuntimeSessionRunner.kt`、`SessionTurnRecorder.kt` | 收 23 种 `AgentEvent`，自己拼条目 | 收 `ThreadEvent`，把 `ThreadItem` 一对一换成 `ConversationItem`（见下表）。拼装逻辑大部分删掉 |
| `RuntimeConfirmation.kt` | `resumeConfirmation` 之后查 `getRuntimeResult` | `approve()` / `deny()`，后半段在原来的流里 |
| 自动批准模式（`ConfirmationMode`） | Bothy 决定后调 `resumeConfirmation` | 还是 Bothy 的策略；看到 `Approval` 条目就按模式调 `approve(THREAD)` 或 `deny()` |
| `FollowUpReply.kt` 的回答框 | Bothy 自己套 | 删掉，SDK 负责（取决于第 9 节第 1 问） |
| `RuntimeSteering.kt`、`RuntimeInput.kt` | `postUserInput` | `thread.steer` |
| `A2aEvents.kt`、`AppA2aRequestHandler.kt` | 按 `AgentEvent` 和 `RuntimeResult` 算 A2A 状态 | 按 `TurnResult.status` 算：`WAITING_FOR_ANSWER` 对应 `INPUT_REQUIRED` |
| 「轨迹」「调试」页（`RunEvidence.kt` 等） | `getRuntimeEvidence` / `getRuntimeTraceEvents` / 事件日志方法 | `runtime.diagnostics` |
| 直接 import kernel-core 的 8 个文件 | 用内部符号；其中两处用 `KernelRuntimeBuilder` 搭只有内核的运行时 | kernel-core 并进去以后这些符号都没了，要改用公开替代。`PromptStats`、`ContextUsage` 需要的提示词统计，加进 `diagnostics`；`FixtureModelClient` 一类测试模型只用 `ModelClient`；用 `KernelRuntimeBuilder` 的改用 `AgentRuntime.builder()` |

条目对照：

| `ThreadItem` | Bothy `ConversationItem` |
|---|---|
| `UserMessage` | `TurnHeader` |
| `AgentMessage` | `MessageItem(ASSISTANT)`，最后一条 `final = true` |
| `Reasoning` | `MessageItem(THINKING)` |
| `ToolCall`、`WebSearch`、`FileChange` | `ToolCallItem` |
| `Approval` | `ConfirmationItem` |
| `Question` | `MessageItem(ASSISTANT)` 加问题卡片 |
| `Notice` | `MessageItem(STATUS)` 或 `NOTICE` |
| `Error` | `MessageItem(ERROR)` |

Bothy 的协议（agent-app-protocol）这一步不用改，只是服务端的映射变薄了。以后可以考虑让 `ConversationItem` 直接用 `ThreadItem` 的形状。

验证：Bothy validate；平板上走一遍第 10 节的设备检查。

## 9. 决定

### 9.1 已定

- **0.5.0 直接用新 API 替换旧 API**，不留过渡版本（owner 11-06）。
- **kernel-core 并进 agent-runtime**，改成 Kotlin 的 `internal`，不再单独发布（owner 11-06）。现在依赖 kernel-core 的只有 agent-runtime 和 `samples/minimal`（它用 `KernelRuntimeBuilder` 搭只有内核的运行时）；可选模块都只依赖 kernel-api，不受影响。要跟着改的有：`settings.gradle.kts`、BOM、根构建里的 `desugaringModules`、kernel-core 的 38 个测试文件、Bothy 里 import kernel-core 的 8 个文件（第 8 节）。只有内核的那条路（`KernelRuntimeBuilder` / `CoreAgentRuntime`）删掉或改成 internal，`samples/minimal` 改用 `AgentRuntime.builder()` 或删掉。

### 9.2 待定（每项附推荐）

**1. 提问是否结束当轮。**

Codex 的做法（看的是本地 codex-rs 源码，2026-10-17 的版本）：

- 默认模式没有提问工具。模型把问题写在回复里，这一轮就结束，用户下一句话就是回答。
- `request_user_input` 默认只在 Plan 模式开放（另有功能开关能在默认模式打开），而且要在配置里开 `experimental_request_user_input_enabled`。一次问 1 到 3 个问题，每题给 2 到 3 个选项，客户端自动加一个「其他」。这一轮挂起等回答，回答作为工具结果交回模型；app-server 通过 `item/tool/requestUserInput` 请求客户端。子 agent 不能用。
- 实验中的 `request_user_input_async`：问题作为一条最终回复显示，带着问题和选项；工具立刻返回 `{"accepted":true}`，不等回答，回答就是用户的下一句话。
- 也就是说，Codex 只在 Plan 模式（人正坐在键盘前一起定计划）让一轮停着等人。

我们现在也有两个提问工具：

- `ask`：缺了信息就什么都做不了时用。这一轮结束（WAITING_USER_INPUT），回答来了用 `resumeUserInput` 接着跑同一个 run。状态已落盘，所以中间隔多久都能接上。
- `ask_async`：一边问一边干活，最多 3 题，问题卡片显示出来。回答作为新的用户消息，在这一轮还在跑时插进来；这一轮结束时还没回答的问题作废。
- 两个工具子 agent 都不能用（`userReachable = !childRun`）。

选项：

- **A（推荐）**：`ask` 结束当轮，下一轮的输入就是回答；`ask_async` 在当轮里给出问题条目，回答用 `steer`（4.6）。理由有三条：跟 Codex 的默认模式一致；车上的人常常不回答；现在的实现本来就是这样，改动最小。
- B：`ask` 也像 Codex 的 Plan 模式那样，当轮一直挂着等回答。这只适合有人盯着屏幕的场景，以后需要可以加成选项。

**2. 工具调用条目给不给原文。**

举个例子，假设有个查快递的 MCP 工具：

- 用户说：「帮我查一下快递 SF1234567890123 到哪了」。
- 模型调 `mcp__express__track`，参数是 `{"tracking_no":"SF1234567890123"}`，工具返回 `{"status":"派送中","courier_phone":"13912345678","eta":"今天 18:00"}`。

现在三处看到的东西：

| 哪里 | 看到的 |
|---|---|
| SDK 发给宿主的事件 | `NativeToolCallStarted(arguments = {"redacted":true,"sensitive":true})`，`NativeToolCallFinished(result = {"redacted":true,"sensitive":true})`。参数和结果不管有没有敏感内容都换成占位，过滤器会改动的再加 `sensitive`。只有 `update_todo` 的结果例外 |
| Bothy 的工具卡片 | `{"tracking_no":"SF[REDACTED_PHONE]"}` 和 `{"status":"派送中","courier_phone":"[REDACTED_PHONE]","eta":"今天 18:00"}`。Bothy 拿不到原文，只好另挂一个 hook（`RawToolTranscriptHook`），在工具执行前后取原文，自己过滤（`ToolTranscriptRedactor.kt`：phone、token、address 等字段整个换成 `[REDACTED]`，再用默认过滤器把 10 位以上的数字换成 `[REDACTED_PHONE]`），再回填到占位卡片上 |
| 最终答案（现在不过滤） | 「您的快递 SF1234567890123 正在派送中，快递员电话 13912345678，预计今天 18:00 送到。」 |

同一个号码，答案里看得到，卡片上被遮住。Codex 的工具条目给原文：`McpToolCallItem` 有 `arguments` 和 `result`，`CommandExecutionItem` 有 `command` 和 `aggregated_output`。

选项：

- **推荐：给原文**，和最终答案一致。宿主要遮，就在画卡片时自己遮（Bothy 保留 `redactToolPayload`），那个 hook 和占位回填就可以删了。落盘的事件日志和 trace 照旧按现在的规则过滤，所以重启后 `thread.items()` 读回来的卡片可能和现场看到的不完全一样。这一点写进 KDoc。
- 维持现状：继续给占位，宿主要画卡片就得像 Bothy 那样自己挂 hook 取原文。

**4. 协议请求整套删掉吗。**

- 是什么：本意是「运行时请宿主办一件事，等宿主办完再继续」。一轮跑到某处生成一个 `RuntimeProtocolRequest(kind, payloadJson, 过期时间)`，run 进入 WAITING_PROTOCOL_REQUEST；宿主用 `resolve` / `reject` / `cancel` 三个方法回应，用 `getPendingProtocolRequests` 查还没回应的。
- 现状：唯一会生成它的是 builder 的 `protocolRequestOnRun`。这个方法是 internal，注释写的是「验证用的开发钩子」。宿主没有任何办法让运行时发出协议请求，所以这 4 个方法、1 种事件和一组结果类型（kernel-api dump 里约 165 行）对宿主都没用。Bothy 也没用。
- **推荐：整套删掉**，连同 `ProtocolRequestController`、脚手架和相关测试。真要「请宿主办事」，现成的路是宿主提供一个工具（`ToolProvider`）。要异步等宿主的，以后加一种 `PendingRequest`，和批准走同一套。

**5. 插件系统删掉吗。**

- 是什么：`AgentPlugin` 带一份描述（id、版本、要求的内核 API 版本范围），`install` 时往 `AgentPluginScope` 里登记能力（`CapabilityKey`，限定只能有一个还是可以多个）。`AgentProfile` 选用哪些能力，`KernelApiVersion` 一族做版本兼容检查。本意是让第三方模块按版本协商插进内核。
- 现状：只有 kernel-api、kernel-core、agent-runtime 自己在用：builder 的 `plugin()`、`profile()`，加上内部组装用的 `ProfileCompiler`、`RuntimeBlueprint`。可选模块（exec-js、android-use、device-capabilities）都直接通过 builder 方法和 SPI（`ToolProvider`、`ExecEngine`）接进来，Bothy 也没用。它在 kernel-api dump 里约 145 行。
- **推荐：公开部分删掉**，组装逻辑随 kernel-core 变成 agent-runtime 的 internal。扩展只走第 5 节那些 SPI，版本兼容交给 Maven 版本号和 BOM。

**6. 已批准的四项破坏性改动放不放进 0.5.0。** 原定发版、Bothy 迁移之后再做。四项分别是：

- **移动 `ServerSideSearchCapability`**：它描述某家模型服务自带的联网搜索，是具体厂商的事，却放在中立的 kernel-api model 包里。挪到 agent-runtime 的模型客户端旁边。Bothy 有 4 个文件要改 import：`RuntimeBuilder.kt`、`HostedSearchValidation.kt`、`ProviderConfig.kt`、`OnDeviceHost.kt`。
- **给 `ModelMessage` 加推理字段**：模型的推理内容（比如 DeepSeek 的 reasoning_content、Anthropic 的 thinking 签名）现在在历史里另走一路。加一个正式字段后，宿主自己写的 `ModelClient` 也能把推理原样带回下一轮。Bothy 很多地方构造 `ModelMessage`，但新字段有默认值，影响不大。
- **必须指定模型**：现在不给模型也能 `build()`，每次运行都以「没配置模型」结束。改成 `build()` 时直接报错。
- **默认值改中性**：CHANGELOG 已经预告过，这几个默认值以后会变：记忆的六个内置话题（空调、导航、媒体、网页、屏幕、电话）改成没有内置话题；结束语检查去掉认中文说法的部分；协议段不再建议必应 RSS 搜索。Bothy 要在 builder 里把它需要的显式写上。

**推荐：都放进 0.5.0。** 0.5.0 已经要求所有宿主迁一次，这几项再拖一个版本，就是第二次破坏性升级。Bothy 本来就要在发布前本地迁，正好一起把这些值写上。

**7. 名字和包。**

会话类型叫什么：

- **`AgentThread`（推荐）**：和 Codex（`thread/start`）、Bothy 协议（`thread/list`、`thread/start`）用同一个词，看过 Codex 文档的人一看就懂。不叫 `Thread`，是因为 Kotlin 默认 import `java.lang.Thread`，会撞名。
- `Session`：SDK 里 session 已经指好几样东西（会话存储、会话运行权、会话事件日志），再拿来当主线类型容易混。
- `Conversation`：意思直白，但和 Codex、Bothy 协议的词都对不上。

不管叫什么，新的会话 id 就是现在的 sessionId，已经存下来的会话（Android 的 SQLite）照样能 `resumeThread`。

包怎么放：

- **推荐**：主线 API 放 `cc.noharry.agent`（还在 kernel-api 模块里），宿主 import 最短，比如 `cc.noharry.agent.AgentThread`。扩展接口留在现在的包（`cc.noharry.agent.kernel.api`、`.model`、`.mcp`），可选模块和宿主自己写的 `ModelClient` / `ToolProvider` 少改 import。
- 另一种：趁 0.5.0 把 kernel-api 改名成 `agent-api`，扩展接口挪到 `cc.noharry.agent.spi`。名字更干净（kernel-core 没了，「kernel」只剩个空名），但 Maven 坐标变了，所有可选模块和宿主的 import 都要改。

**8. agent-app-protocol / agent-app-server 要不要并进 SDK。**

- 是什么：Bothy 里的三个模块。agent-app-protocol 是线上格式：threads / turns / items 的 JSON-RPC 方法和通知，加上批准请求。agent-app-server 把运行时包成一个服务，管会话列表、转录存储、批准模式、A2A 宿主和对外端口；宿主通过 `AppServerConfig` 给人设、插件和端口。agent-app-sample 是最小示例。车机控制台、手机界面、悬浮窗都是这个协议的客户端。
- Codex 的做法：app-server 和 app-server-protocol 就在 codex 仓里，和核心一起发布，VS Code 插件和桌面版都走它。
- 并进来的好处：别的宿主直接拿到一个现成的服务和协议，界面和 agent 分进程、多个前端连同一个 agent。
- 代价：SDK 公开面马上又变大，而我们正在往小里收。agent-app-server 里还有不少应用层的东西（批准模式、待办看板投影、演示状态、证据页），要先分清哪些是通用的。另外，协议里的 `ConversationItem` 和新的 `ThreadItem` 会是两套几乎一样的类型。
- **推荐：0.5.0 不并。** 等 Bothy 迁完、映射层变薄以后，先让协议直接用 `ThreadItem` 的 JSON 形状。到那时，如果 agent-app-protocol 确实没有 Android 依赖，就把它作为可选模块挪进 SDK；agent-app-server 再看情况。

### 9.3 一步到位的方案（owner 已于 2026-11-06 确认）

**原则**：凡是以后再改就要破坏兼容的，这一版一次定死；以后能加、加了也不破坏兼容的，不挤进这一版。0.5.0 之后，SDK 和 Bothy 都不应该再有结构性的迁移。

**SDK 这一版要做的：**

1. 9.2 的第 1、2、4、5、6、8 问按推荐来。
2. 第 7 问改成一次改到最终名字。模块 `agent-kernel-api` 改名 `agent-api`；主线 API 放 `cc.noharry.agent`，扩展接口放 `cc.noharry.agent.spi`（含 `.model`、`.mcp`）。kernel-core 没了以后，「kernel」只剩一个空名；现在不改，以后改就是又一次破坏性升级。反正这一版所有 import 都要动。
3. 加批准策略：`ThreadOptions.approvalPolicy`，每轮可以用 `TurnOptions` 覆盖。取值：
   - `ASK`：每次都问。
   - `REMEMBER`：同一会话里批准过的工具区域不再问。
   - `APPROVE_ALL`：全部自动批准。
   - `DENY_ALL`：全部自动拒绝。

   策略能自己决定的，不发 `Approval` 条目，只在 `ToolCall` 条目上记一笔是谁批的。这四种正好对应 Bothy 的 `ConfirmationMode`，Bothy 的 `autoDecision`、`shouldSuppressConfirmationCard`、`ConfirmationDomains.kt` 都可以删掉。Codex 的 `ThreadOptions.approvalPolicy` 也是这么做的。
4. 会话历史落盘。现在 `SessionHistory` 只在内存里，所以 Bothy 在 `PreviousExchange.kt` 里把上一轮塞进输入当回顾。这一版让 `resumeThread` 从存储里接回模型历史（Android 存 app 私有目录，复用现有的会话存储），`thread.items()` 也从同一处读。Codex 的会话也是落盘的，resume 时重放。
5. `AgentMessage` 带 `phase`，区分过程中的话和最终答案，Bothy 的 `final` 可以直接对上。Codex 的 agent message 也有这个区分。
6. 默认 `ToolDomainNaming` 认得带 `_` 的 MCP server id。现在 `mcp__device_media__` 会被归到 other。

**Bothy 同一步要做的**（发布前在本地分支做完，0.5.0 发布后推送）：

- 迁到新 API（第 8 节）。删掉：`RawToolTranscriptHook` 和占位回填、`FollowUpReply` 的回答框、`PreviousExchange` 的回顾、自动批准逻辑、对 kernel-core 内部符号的引用。
- 在 builder 里显式写上它依赖的旧默认值：记忆话题、中文结束语检查、搜索站点。
- 补上之前因为要等发版而搁着的几件：A2A 服务端加鉴权、自定义 `traceRedactor`、通知渠道名。
- 协议（agent-app-protocol）的形状不改，服务端的映射变成一一对应。

**不进这一版**（以后加，不破坏兼容）：

- app server 并进 SDK。
- Java 阻塞包装（4.7）。
- `ask` 当轮等待的选项。
- Bothy 界面上发图片（SDK 这一版支持图片输入）。

**顺序**：按第 10 节走。区别是 Bothy 的本地分支从第 2 步起就跟着 SDK 分支，每一步都拿 Bothy 编一遍，接口不顺手当场改。上面第 3、4、5 项插在第 3、4 步旁边做。两边推送后，知识体系文章重新固定链接，并按新代码更新正文。

## 10. 分步计划与验证

每步都要过：

- `./gradlew -q apiDump`，再跑 `./gradlew -q --continue ciCheck`。ciCheck 包括源文件规则、API dump、animal-sniffer、lint、dokka 和测试。
- 改公开 API 时同步 CHANGELOG。
- 每步独立评审后推送。

第 1 到 8 步都在 SDK 的 `api-redesign` 分支上做（第 8 节说了原因）。

| 步 | 做什么 | 怎么测 |
|---|---|---|
| 0 | 等 owner 定第 9 节 | — |
| 1 | 加新类型：`ThreadEvent`、`ThreadItem`、`PendingRequest`、`TurnInput`、`TurnResult` 等。再写一层把循环内部的事件转成 `ThreadEvent` 的转换 | 单测：用录下来的事件序列转换，检查 4.4 的顺序规则 |
| 2 | 在 `DefaultAgentRuntime` 上实现 `AgentThread`：`startThread` / `resumeThread` / `run` / `runStreamed` / `steer` / `interrupt` / `status` / `items` | 把 `samples/extensions` 和 `quickstart-jvm` 改成新 API；CI 的 `published` job 照跑 |
| 3 | 批准改成当轮等待（循环挂起，不再结束），删掉 `resumeConfirmation` 那套重新接入的路径 | 批准、拒绝、过期、等待中取消流、两个界面同时回应，各一个测试；原有确认测试改写到新 API |
| 4 | 提问和回答绑定（按第 1 问的决定），把回答框从 Bothy 挪进 SDK | 提问 → 下一轮回答；提问 → 下一轮说别的；带 `answering` 显式绑定 |
| 5 | 图片输入：`InputPart.Image` 一路进 `ModelMessage.images`；模型不收图时报错 | 单测加 ScriptedModel；平板上发一张图问内容 |
| 6 | 加 `RuntimeDiagnostics`；agent-a2a 的服务端改用新 API；删掉旧的 `AgentRuntime` 方法、`AgentRequest`、公开的 `AgentEvent`；把现有测试都改写到新 API | A2A 测试；`A2aServerTest` 等照跑 |
| 7 | builder 分组；按第 9 节第 6 问的决定处理那四项 | ciCheck |
| 8 | 按第 6 节收进内部：kernel-core 并进 agent-runtime（源码、测试和 `samples/minimal` 跟着搬；只有内核的那条路 `KernelRuntimeBuilder` / `CoreAgentRuntime` 删掉或改 internal；BOM、`desugaringModules`、settings 去掉 kernel-core）；reliability、协议请求、插件系统按第 9 节的决定处理 | dump 达到第 6 节的目标；ciCheck 全过 |
| 9 | Bothy 本地分支对着 SDK 分支迁移（第 8 节）；新 API 要改的回到 SDK 分支改 | Bothy validate 和下面的平板检查 |
| 10 | SDK 分支合并进 main；写 CHANGELOG 0.5.0（破坏性改动和迁移对照）；owner 点头后发布 0.5.0 | `publishToMavenLocal` 加 samples；GitHub CI |
| 11 | Bothy 的依赖换成 Maven 0.5.0，评审后推送 | Bothy validate；平板再走一遍 |

**平板检查**（HA2H3Y7F；装包前先告诉 owner，装完恢复无障碍服务和录音权限）。只做短检查，长时间模拟要 owner 点名才跑：

1. 打字问一句：流式文字出现，最终答案完整。
2. 一次工具调用：卡片上有工具名、参数和结果。
3. 批准卡片：批准一次、拒绝一次；批准后的回复在同一轮里出来。
4. 提问和回答：agent 提问，下一句回答，接得上。
5. 跑着的时候插话（steer）。
6. A2A：对 127.0.0.1:8766 发一次 `message/send`，结果是 completed。
7. 走 app-server 协议（`localabstract:bothy`）用 `turn_probe.py` 跑一轮。
8. 杀掉进程再打开：会话列表还在，`thread.items()` 能重画历史。

## 附：和 Codex 的对照

| | Codex TS SDK | Codex app-server | 现在 | 这份设计 |
|---|---|---|---|---|
| 入口 | `new Codex()` | 进程加 JSON-RPC | `AgentRuntimeBuilder.create().build()` | `AgentRuntime.builder().build()` |
| 会话 | `startThread` / `resumeThread` | `thread/start`、`thread/resume` | 宿主给 `sessionId` | `startThread` / `resumeThread` |
| 一轮 | `run` / `runStreamed` | `turn/start` | `run(AgentRequest)` | `run` / `runStreamed` |
| 事件 | 8 种 | `turn/*`、`item/*` 通知 | 23 种 | 8 种 |
| 条目 | 8 种 | 更多（含命令、补丁） | 没有，宿主自己拼 | 10 种左右 |
| 批准 | 不支持（按策略自动） | 服务端发 `item/*/requestApproval` 请求 | `ConfirmationRequired` 加 `resumeConfirmation`，后半段不在流里 | `Approval` 条目，当轮等待 |
| 提问 | 不支持 | `item/tool/requestUserInput` | `RuntimeBlocked` 加 `resumeUserInput` | `Question` 条目，下一轮回答 |
| 插话 | — | `turn/steer` | `postUserInput` | `steer` |
| 中断 | `AbortSignal` | `turn/interrupt` | 取消 Flow | `interrupt()` 或取消 Flow |
| 输入 | 文字加本地图片 | 文字、图片、文件 | 只有字符串 | 文字、图片、附件 |


## 实现核对（2026-11-06）

模块已收为 `agent-api` + `agent-runtime`；原 kernel-core 源码和测试并入 runtime，旧引擎契约只在内部使用。
公开主线和 SPI 已使用最终包名；高级诊断仍标实验性。API dump 的量化结果为：agent-api 主线根包 649 行（入口 AgentRuntime 另在 runtime 模块）、
agent-api 总计 2,437 行，runtime 1,829 行，所有发布模块合计 5,347 行（基线约 9,400 行）。
这超过第 6 节的 600 / 2,000 行体积目标；统计包含 Kotlin data class 的 JVM 生成成员，以及为保留
既有事件日志扩展能力所需的公开载荷类型。实现没有为缩小数字而隐藏宿主必需类型。

测试和最终交付状态记录在同目录交接文件中。
