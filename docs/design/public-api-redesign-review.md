# 0.5.0 实现评审与修复交接

> 写于 2026-11-07，给接手修复的模型。先读完这份，再按需查 `public-api-redesign.md`（设计稿）和 `public-api-redesign-handoff.md`（仓库、规矩、命令）。
> 评审对象：SDK `dc90409`「feat!: replace the public runtime API with threads and turns」；Bothy 分支 `codex/api-redesign`（`724125f0`、`6713dd08`，基于 `c6502c86`）。
> 评审方法：四路只读评审（公开 API、会话运行逻辑、持久化和隐私、Bothy 迁移），每条都按代码路径追过，没有运行。标了 ✔ 的几条又由我亲自读代码核对过。行号以 `dc90409` 和 `6713dd08` 为准，动手前请自己再看一遍。
> 和 owner 交流用中文。

## 0. 收尾状态（2026-11-07，后写的，以这一节为准）

下面第 1 到第 9 节是修复前写的评审，留作记录。修复轮之后又做了一轮独立核对，结论如下。

**SDK（分支 `codex/api-redesign`）**

- 第 4.1、4.2、4.3 节列的问题都已修好，下面三条除外，作为后续：
  - 4.2 第 6 条：旧的暂停/恢复代码已是 internal，公开 API 调不到，但代码还没删；
  - 4.3 第 7 条：`resumeThread` 仍在调用线程上读写一个小的元数据文件，已写进 KDoc，选项没变时不再写盘；
  - 4.3 第 8 条：进程在等批准时被杀，恢复后模型会看到这次调用已取消，但界面上不补一条提示。
- 核对时发现修复轮自己又带进了几个问题，也已修好：
  - 一轮里图片超过约 2 MB，整个模型历史会被清空。现在先去掉较早几轮的图片，最新一轮总是保留。
  - 一个读不了的运行记录，或者一次失败的日志写入，会让之后的删除全部失败。
  - 批准过期后，等待期间用户插的话会丢。现在过期和拒绝一样，接着跑。
  - 字符串里嵌着的 JSON，过滤后会变成非法 JSON。
  - 在「每次都问」下，同一轮里同一个网站会反复询问。现在同一轮里批准过的网站不再问。
- 另外新增：宿主在一轮进行中通过 `resumeThread` 改批准策略，正在跑的那一轮和它正在等的批准立刻按新策略处理（Bothy 跑到一半切换模式要用）。
- 每个修复都带了测试。2026-11-07 在这批修复上跑了完整的 `apiCheck` 和 `ciCheck`：1,848 个测试，0 失败，2 个跳过。Bothy 本地分支对着这个 SDK 分支跑 `scripts/validate.py` 也全部通过。
- 搁置不做的：每个会话的存储上限（`ThreadStorageLimits`，本地分支 `codex/api-storage-repair` 上的 WIP 提交 `1b8fbeb`，还编不过）。这是新增功能，不破坏兼容，可以晚于 0.5.0；另外「回执满了就拒绝新轮次」这个设计对长期会话不友好，做之前要先定。
- 早就存在、这次没修的：Android 上关闭运行时不会关闭 SQLite 数据库（`AndroidSqliteSessionStore` 不是 `AutoCloseable`），会等 GC 回收。

**Bothy（本地分支 `codex/api-redesign`，`~/project/bothy-api`，未推送）**

- 第 5 节的问题都已修：
  - 插话按请求 id 显示：用户说的话是插话气泡；计划提醒、委托报告只显示运行行。
  - 最终答案经过 `shownReply()`，不再出现「TTS:」开头的行。
  - 陪聊和计划提醒的上下文接回来了；超长时截短，不再让下一轮失败。
  - A2A 令牌在设置里能看到。
  - 「批准过的不再问」下批准一次会记住；卡片只显示网站名；跑到一半切换模式会同步给正在跑的那一轮。
  - 子 agent 用父会话的批准策略。
  - 一轮里有工具失败时，结束时写「Runtime result」行，手机靠它判断失败。
  - A2A 进度和看板恢复成 Bothy 自己的说法。
- 修了两个测试问题：
  - 一个会一直挂住的测试：它按旧的 id 订阅事件，永远等不到；
  - 四个按执行 id 查事件的测试：事件日志里 `runId` 现在是执行 id，按轮次查要用 `requestId`。
- 早就存在、这次没修的：`bothy-app` 的设备测试 `TrajectoryColdReadDeviceTest` 从 11-04 拆 server 起就编不过。它用到的类搬到了 agent-app-server 的包里，而且是 internal；同时它又要用 bothy-app 自己的东西。要修得把它挪到合适的模块并重写，validate 和 CI 都不编它。
- 推送前还要做：把所有 checkpoint 提交合成一个，去掉 harness trace 文件，依赖改成 Maven 上的 0.5.0（发布之后）。

## 1. 结论

- 方向和主体结构是对的，设计稿的大部分内容都做出来了，CI 也是绿的。第 9 节列了确认没问题的部分，不要动。
- **0.5.0 先不发。** 0.5.0 一发，主线 API 和扩展接口就要承诺稳定，现在还有一批公开 API 的问题和行为 bug，发布后再改就是破坏兼容。
- **Bothy 分支先不推。** 有几处用户看得见的倒退，提交也要整理。
- 修复的范围和顺序见第 4 到第 8 节。第 2 节的几件事要先问 owner。

## 2. 动手前先问 owner 的事

每项都附了我的推荐。owner 没回答之前，不要实现这几项。

1. **拒绝之后，这一轮是结束还是接着跑？**
   - 现在：拒绝会让整轮以 `TurnFailed(CONFIRMATION_DENIED)` 结束，测试也锁定了这个行为。
   - 设计稿 4.6 写的是拒绝之后同一个流接着跑。
   - 后果：在「全部拒绝」下，每个要批准的轮次都会直接失败，模型连解释的机会都没有。
   - **推荐：接着跑。** 把「用户拒绝」作为工具结果交给模型，让模型告诉用户这件事做不了。
2. **存盘时，最终答案和用户的话要不要遮？**
   - 现在：会话快照里的最终答案和用户输入都过了电话号码规则，重启后显示的和当时不一样。这和 owner 之前定的「答案不过滤」矛盾。
   - **推荐：不遮。** 工具的参数和结果按 JSON 结构过滤（见 4.3 第 5 条），过滤规则和事件日志一致。
3. **Bothy 关掉电话号码和位置过滤，同不同意？**
   - 现在：`SharedRuntimeAssembly.kt:21` 用 `phones = false, locations = false`。后果是 trace 和会话存储会把电话号码和位置明文写到磁盘，超出了设计稿「落盘照旧过滤」的说法。
   - **推荐：落盘照旧过滤。** 界面拿到的条目本来就是原文，Bothy 没必要为了显示关掉落盘过滤。
4. **0.4.0 留下的旧会话怎么处理？**（影响小，可以和上面几项一起问）
   - 现在：`listThreads` 看不到旧会话，`items()` 是空的（4.3 第 2 条）。
   - **推荐：只在 CHANGELOG 里写清楚，不做导入。** 0.4.0 的模型历史本来就只在内存里；Bothy 自己有转录存储，用户感觉不到。

### Owner 已确认的处理方式（本轮修复）

1. 拒绝批准后继续运行，把拒绝作为工具结果交给模型。
2. 最终答案和用户输入持久化时保留原文；工具参数/结果按 JSON 结构过滤。
3. Bothy 保持关闭电话号码和位置过滤。这是 owner 的明确选择，覆盖上面的推荐；密钥仍过滤。
4. 不导入 0.4.0 旧历史，只在 CHANGELOG 说明。

以上四项已确认，不再作为修复阻塞。Maven 发布仍未授权。

## 3. 现状和注意事项

- **SDK**：`dc90409` 已经推到 `origin/main` 和 `origin/codex/api-redesign`，两个工作区都在这个提交上：
  - 主工作区 `~/project/android-agent-runtime`（main）；
  - `~/project/android-agent-runtime-api`（`codex/api-redesign`）。
- **0.5.0 没发**，`VERSION_NAME` 还是 `0.5.0-SNAPSHOT`。发布要 owner 点头。
- **本机 Bothy 编不过了。** 停在 main 的几个 Bothy 目录（`~/project/bothy-server`、`~/project/bothy`、`~/project/bothy-pr5`）默认编旁边的 SDK 源码；SDK main 已经换成新 API，这些目录现在都编不过。要编的话加 `-PagentRuntimeDir=`，改用 Maven 上的 0.4.0。Bothy 在 GitHub 上的发布流程本来就用 Maven，不受影响。
- **Bothy 的迁移**在 `~/project/bothy-api`（分支 `codex/api-redesign`），没推送。它编的是 `~/project/android-agent-runtime`（也就是 SDK main）。
- **修复怎么提交**：
  - 在 `codex/api-redesign` 上按组提交：公开 API、行为、持久化、文档，各一组。
  - 每组做一次独立评审再推送。
  - 全部做完再把 main 快进过去。
  - 不要再把几十个文件塞进一个提交。
- 规矩照 `public-api-redesign-handoff.md` 第 4 节。尤其注意两条：
  - 用 NoHarry 身份提交；
  - 新 API 发布到 Maven 之前，Bothy 不能推送用到它的代码。

## 4. SDK 必须修的

### 4.1 公开 API（发布后再改就是破坏兼容）

1. **✔ `resumeThread` 只要带了任何选项，就会把存下来的批准策略改回 `ASK`。**
   - 位置：`agent-api/.../TurnInput.kt:31-38`（`approvalPolicy` 不能为空，默认 `ASK`）；`agent-runtime/.../thread/DefaultThreadRuntime.kt:52-56`（只有选项完全等于默认值时才保留存下来的策略）。
   - 例子：`resumeThread(id, ThreadOptions(scene = "x"))` 会把存下来的 `APPROVE_ALL` / `REMEMBER` / `DENY_ALL` 改成 `ASK`。model、workspace、scene、instructions 也没法在恢复时清空。
   - 修法：`approvalPolicy` 改成可空（null 表示沿用），逐字段合并。KDoc 写明 null 就是沿用。
   - 测试：恢复会话时只改 scene，批准策略保持不变。
2. **✔ `settings()` 会冲掉前面 `limits()` 设好的预算。**
   - 位置：`AgentRuntimeBuilder.kt:420-424` 无条件调用 `limits(settings.limits)`。
   - 例子：`.limits(LoopLimits(maxModelRounds = 50)).settings(LoopSettings(narration = c))` 会把轮数、工具调用数、时间和重试预算都恢复成默认值。
   - 修法：从 `LoopSettings` 里去掉 `limits`（它有自己的 builder 方法），或者改成可空、只在非空时应用。
3. **稳定和实验标记反了。**
   - `NarrationCheck`（`spi/NarrationCheck.kt:12`）、`ServiceAnswerCheck`（`:14`）和 `LoopSettings` 里的细项类型（`RuntimeMemoryRetriever`、`MemoryTopic`、`RuntimeMemoryPolicy`、`RuntimeConcurrency`、`BackendTools`、`NetworkToolConfirmationPolicy`）现在是稳定的。按设计稿第 5 节，它们应该标 experimental。
   - 反过来，`LoopLimits`（`LoopSettings.kt:53`）标了 experimental，宿主设个轮数都要 opt-in。它应该是稳定的。
   - `NarrationCheck` 的文档第 5 行还说默认认中文说法，实际已经只认英文了。
4. **没人能用的公开接口，冻结前要收进内部。**
   - `WorkspacePort` 一族（`spi/WorkspacePort.kt:9`，约 77 行 dump）没有任何公开方法接收或返回它，文档还写着 Plugin-facing。
   - `RuntimeHookRegistration`（`spi/RuntimeHooks.kt:69`）也一样。
5. **漏成公开的内部名字。**
   - `RuntimeCompletionGate.kt:20`：给 internal 的 `decideFinish` 加了 `@JvmOverloads`，结果生成了一个公开的重载，进了 dump。去掉这个注解。
   - `openFileBackedSessionEventLog`（`runtime/eventlog/DurableSessionEventLogPlugin.kt:58`）应该公开（agent-runtime-android 要用），但 Java 看到的类名是 `DurableSessionEventLogPluginKt`，文档还提到已删除的插件。加 `@file:JvmName` 或者把它挪到别的文件。
6. **`AgentRuntime` 接口在 agent-runtime 模块里（`agent-runtime/.../AgentRuntime.kt:8`），不在 agent-api 里。**
   - 后果：`cc.noharry.agent` 包被拆在两个构件里；一个只想接收 `AgentRuntime` 的库，也得依赖整个实现。
   - 修法：接口挪到 agent-api。`AgentRuntime.builder()` 改成 agent-runtime 里对 `Companion` 的扩展函数，Java 入口用 `AgentRuntimes.builder()` 或保留 `AgentRuntimeBuilder.create()`。
7. **错误码是没有公开列表的字符串。**
   - `TurnError.code`（`TurnInput.kt:74`）和 `ThreadItem.Error.code`（`ThreadItem.kt:82`）都是 `String`。`IMAGE_INPUT_UNSUPPORTED`、`INTERRUPTED`、`TURN_FAILED`、`MISSING_TURN_RESULT`、`THREAD_BUSY`、`QUESTION_NOT_FOUND`（`DefaultAgentThread.kt:122-133`）都不在 `RuntimeErrorCode` 里，也没有文档。
   - 修法：保留字符串，加一个公开的常量对象（比如 `TurnErrorCodes`），每个码写一句说明。
8. **扩展接口里还用旧的 id 名字。**
   - `HookContext`、`ToolCallContext`、`RuntimeContextRequest`、`SessionEvent` 里还是 `sessionId` / `runId` / `requestId`，冻结后再改名就是破坏兼容。
   - 推荐：趁现在改成 `threadId` / `turnId`。至少也要在 KDoc 里写明它们分别等于哪个。
9. **公开面还是太大。** 主线约 671 行（目标 600 行），稳定的主线加扩展接口约 3,600 行（目标 2,000 行）。
   - 收进内部：上面第 4、5 条的东西；其他模块都不用的 VFS 内部类（`VfsSnapshot*`、`VfsMountTable`、`VfsPathResolver`、`VfsManifestLoader`、`VfsResolvedMount`，约 130 行）。
   - 改成 experimental：`SessionEventPayload` 和 `SessionJson`（约 320 行，只有自己写事件日志的宿主才需要）。
   - 改完重新量，在 CHANGELOG 里写实际数字。
10. **CHANGELOG 的 0.5.0 一节不完整，还自相矛盾。**
    - 迁移表（第 35-53 行）只有 3 行。要补齐设计稿 7.1、7.2 的完整对照，加上 `cancelPendingConfirmation`、`KernelRuntimeBuilder` / `CoreAgentRuntime`（改用 `AgentRuntime.builder()`）、`sessionStore`。
    - 删掉或改写描述已删 API 的旧条目：第 57-62、66-69、77-79、88-99 行；第 213 行说 `traceRedactor` 是 experimental，实际已经是稳定的。

### 4.2 行为 bug

1. **幂等的轮次 id 是坏的。**
   - 位置：`thread/ThreadInput.kt:22` 把 `turnId` 直接当成引擎的全局 run id；`RunStateController.kt:104-106`、`DefaultAgentRuntime.kt:306`；会话层只在本会话里查重放（`DefaultAgentThread.kt:62,76`）。
   - 后果：
     - 开了 `storage(dir)` 以后，进程在一轮中途崩掉，再用同一个 `turnId` 重试，会得到 `DUPLICATE_RUN_ID`。这个失败还会被存下来，之后每次重试都重放这个失败。
     - 两个会话用了同一个 `turnId` 也会冲突。
   - 修法：
     - 引擎的 run id 和 `turnId` 脱钩（比如随机 id），会话存储里记下 `turnId` 到 run id 的对应。
     - 崩溃后重试时，如果找到了旧 run 但这个会话没有存下结果，把旧 run 记为中断，重新开一轮。
   - 测试：崩溃后重试；两个会话用相同的 `turnId`。
2. **✔ 普通插话会被绑成最新那个异步提问的回答。**
   - 位置：`thread/DefaultAgentThread.kt:170-176`：`answering == null` 时取 `questions.lastOrNull()`。而接口 KDoc（`AgentThread.kt:16-19`）说不指定就是普通插话。
   - 后果：
     - 模型同时问了目的地、预算、日期三个问题，用户插话说「苏州」，模型收到的是「日期问题的回答：苏州」。
     - 某个问题已经收到回答、还没被消费时，普通插话会被拒，返回 `QUESTION_NOT_FOUND`，插话的内容就丢了。
   - 修法：只有显式传了 `answering` 才绑定；普通插话一律当普通用户消息。设计稿 4.6 原来写得含糊，已经改正。
   - 测试：开着多个异步提问时普通插话；指定问题插话。
3. **模型重复用同一个工具调用 id 时，整轮失败。**
   - 位置：`thread/ThreadToolProjection.kt:34-42`、`ThreadEventProjection.kt:208`：一次调用结束就把记录删掉；同一个 id 再来时，`start()` 会撞上「Duplicate item id」的检查而抛异常。
   - 诱因：服务商不给 id 时，OpenAI 客户端统一用 `"openai-tool-call"` 兜底（`OpenAiProviderJson.kt:245,286`）；有些本地或兼容服务器也会在每次回复里复用 id。
   - 修法：条目 id 用这一轮里的序号，不直接用服务商给的 id；OpenAI 的兜底 id 加上序号。
   - 测试：同一轮里两次调用用同一个 id；回复里没有 id。
4. **拒绝后是否接着跑，按第 2 节第 1 问的决定改。** 如果改成接着跑，要同步更新 `ThreadApprovalFlowTest` 等锁定了现有行为的测试，并验证 `DENY_ALL` 下模型能正常回复。
5. **等批准时被取消或中断，模型历史里会一直留着「等待确认」。**
   - 位置：只有拒绝和过期才调用 `recordUnconfirmed`（`DefaultAgentRuntime.kt:657`）；取消时，`NativeToolMessages.kt:72` 写的 "awaiting_confirmation … has not run" 留在了历史里。
   - 修法：取消和中断的路径也记成 `CONFIRMATION_CANCELLED`。
   - 测试：取消一次批准，看下一轮的模型输入。
6. **旧的暂停/恢复路径还编在代码里。** `resumeConfirmation`、`resumeUserInput`、确认存储、`emitToolConfirmation` 都已经是 internal，公开 API 也调不到了，但代码还在。确认没人用之后删掉。

次要的（这一轮顺手修）：

- **刚开始就中断，可能什么事件都没有。**（`DefaultAgentThread.kt:99-102`）任务是 LAZY 启动的，正文还没跑就被取消的话，既没有 `TurnStarted` 也没有结束事件，`run()` 会抛「Turn did not produce a result」。要保证一定发出结束事件。
- **一轮还在跑时删除会话，流直接断，没有结束事件**（`DefaultThreadRuntime.kt:84`、`DefaultAgentThread.kt:96`）。应该以 `TurnFailed(THREAD_DELETED)` 结束。
- **`TurnCompleted` 发出时，这一轮还没释放运行权**（`DefaultAgentThread.kt:89-96` 对比 `:102-107`）。收到完成事件的代码马上发起下一轮，会碰到 `THREAD_BUSY`。要先释放再发事件。
- **等批准时 `steer` 返回 `NOT_RUNNING`**，而 `status()` 显示的是 `WAITING_FOR_APPROVAL`。改成先排队，或者返回一个能说明情况的结果。
- **REMEMBER 记的区域和网络策略对不上。**（`thread/ThreadApproval.kt:71-78`）这里用 `java.net.URI` 取域名，网络策略用的是 OkHttp 的解析。主机名带下划线，或者 URL 前面有空格时，取到的是空值，于是退回到 `"web_fetch"`，批准一次就放行所有这类网址。另外，MCP 服务 id 和主机名共用一个命名空间。修法：统一用 `NetworkToolBackend` 的解析，并给不同来源的区域加前缀区分。
- **`approvedBy` 分不清是人批准过记住的，还是策略自动批准的**，两者都记成 REMEMBER。

### 4.3 持久化和隐私

1. **✔ Android 升级以后，事件日志换了位置。**
   - 位置：`agent-runtime-android/.../AndroidAgentRuntime.kt:35-36` 先设了 `sessionEventLogRoot(.../event-log)`，紧接着调用 `.storage(.../conversations)`，而 `AgentRuntimeBuilder.kt:181` 在 `storage()` 里把它覆盖成了 `conversations/events`。
   - 后果：0.4.0 的日志成了孤儿，诊断接口看不到；公开的辅助函数 `appPrivateSessionEventLog`（`AndroidRuntime.kt:22`）和 CHANGELOG 还指着旧路径。
   - 修法：`storage()` 不覆盖已经设过的事件日志位置。Android 继续用原来的 `event-log` 路径。
   - 测试：先设事件日志位置再调 `storage()`，位置不变。
2. **0.4.0 的旧会话：`listThreads` 看不到，`items()` 是空的。**（`DefaultThreadRuntime.kt:51`、`ThreadStore.kt:58-63`）按第 2 节第 4 问处理，推荐只在 CHANGELOG 里写清楚。会话 id 和旧的 sessionId 一致，新轮次会接着写进同一个 SQLite 会话。
3. **`deleteThread` 删不干净。**
   - 位置：`DefaultThreadRuntime.kt:86-90`、`DefaultAgentRuntime.kt:293-296` 只清了内存和两个 json 文件。
   - 还留着的：事件日志及其图片附件、Android SQLite 里的消息和工具参数、运行记录和 `runs/<turnId>/loop-history.json`（`storage()` 默认会开运行记录，`AgentRuntimeBuilder.kt:182`）。
   - 后果：删除后诊断接口还能查到旧事件；同一个 id 重建会话，新旧事件会混在一起。
   - 修法：
     - 给 `SessionEventLog` 加一个带默认实现的删除方法，再加上 SQLite 和运行记录的删除，都在 `deleteThread` 里调用。
     - 正在跑的轮次照现有测试的做法先停掉。
   - 测试：删除后各处都查不到这个会话的数据。
4. **一个坏文件就能让整个功能报错。**
   - `list()` 解析每个文件时都不处理错误（`ThreadStore.kt:59-60`），一个坏文件或者版本号不认识，`listThreads` 就整个抛异常。
   - `.history.json` 坏了（`ModelHistoryCodec.kt:24`、`ThreadStore.kt:122-126`），这个会话每一轮都失败，连删除都会失败，因为删除前要先读一遍（`SessionHistoryStore.kt:121-131,138-142`）。
   - 存盘失败（比如磁盘满）会以异常形式从 `runStreamed` 抛出，而不是 `TurnFailed`（`DefaultAgentThread.kt:91,132-136`）。
   - 修法：
     - 列表时跳过读不了的文件。
     - 历史文件坏了就改名成 `.corrupt`，当作空历史继续。
     - 删除时不需要先解析文件。
     - 存盘失败时以 `TurnFailed(STORAGE_FAILED)` 结束这一轮。
   - 测试：坏文件、版本号不认识、磁盘写失败。
5. **✔ 存盘的过滤方式不对。**
   - 位置：`thread/ThreadItemCodec.kt:74-76` 对工具参数和结果整串套用文本规则（`runtime/TraceSecurity.kt` 里的那几条）。
   - 漏掉的：`Bearer` 令牌，以及 `"access_token":"…"`、`"api_key":"…"` 这种 JSON 字段里的密钥，会明文写进 `.thread.json`；而同样的内容事件日志是拒收的。
   - 改坏的：10 位以上的数字会被换成不带引号的 `[REDACTED_PHONE]`。比如 `{"ts":1730900000000}` 会变成 `{"ts":[REDACTED_PHONE]}`，重启后读回来的工具结果就不是合法 JSON 了，Bothy 画卡片要解析它。
   - 最终答案和用户的话（`ThreadItemCodec.kt:29,106`，还有 `finalResponse`）重启后也会被遮。
   - 修法：
     - 按 JSON 结构过滤：遍历 JSON，只过滤字符串值；按字段名整个替换敏感字段；补上 `Bearer`。替换完的结果必须仍是合法 JSON。
     - 最终答案和用户的话按第 2 节第 2 问处理（推荐不遮）。
     - `.history.json` 按设计就是原文（包括推理内容和图片），要在 CHANGELOG 里写明。
   - 测试：带密钥形状的工具结果、长数字，重启后 JSON 仍然合法、密钥不在文件里。
6. **存储无限增长。**
   - `.thread.json` 永久保存每一轮，图片用 base64 存在里面（`ThreadItemCodec.kt:107-108`），每一轮还要整个重写两次（`DefaultAgentThread.kt:91,136`）。
   - `list()` 要把每个文件完整解析一遍，而且全程只用一把锁。
   - 历史的 4 MB 上限没算图片、推理内容和签名（`SessionHistoryStore.kt:174-175`）。
   - `liveTurns`（`ThreadStore.kt:30,77`）在进程活着期间一直留着每一轮的完整结果，图片也在里面。
   - 修法：
     - 图片存成附件文件，用哈希引用。
     - 改成按轮追加写，或者每轮一个文件；每轮只存一次。
     - 列表的元数据单独存一份。
     - `liveTurns` 存盘后就释放。
     - 上限把图片和推理内容也算进去。
   - 测试：几十轮带图片的会话，文件大小和内存都有上限。
7. **`resumeThread` 不是挂起函数，却在调用线程上读写磁盘。**（`DefaultThreadRuntime.kt:51,57`、`DefaultAgentThread.kt:29-31`）在 Android 主线程上可能读写几 MB。
   - 推荐：`startThread` / `resumeThread` 保持非挂起，等第一次挂起调用（`run`、`items`、`status`）时再从磁盘加载。
   - 测试：主线程调用 `resumeThread` 不做 I/O（StrictMode 或计时）。
8. **等批准时进程被杀，历史和条目会对不上。**
   - 位置：等批准之前，历史已经存了「等待确认」的占位（`NativeToolLoop.kt:462-463`、`DefaultAgentRuntime.kt:651`），但 `.thread.json` 里没有这一轮。
   - 修法：恢复时发现这种占位，就记成已取消，再补一条 `Notice`。
   - 测试：等批准时杀进程，然后恢复。
9. **没有确认的一点：两个进程同时用同一个存储目录，可能丢数据。** 没有文件锁。至少在 KDoc 里写明一个存储目录只能由一个进程使用。

## 5. Bothy 必须修的

以下路径都在 `~/project/bothy-api/workspace/apps/android/` 下。文件名在仓库里是唯一的，可以直接搜。

1. **✔ 插话变成了一条新的用户消息。**
   - 位置：`session/TranscriptStore.kt:268-269` 只丢掉了「不是插话」的用户消息，所以 SDK 回显的每一条插话都被 `ThreadItemProjection.kt:12` 画成了一个 `TurnHeader`，显示的还是发给模型的原文。
   - 后果：
     - 计划提醒（`RuntimeSession.kt:645`）显示成用户说了「【计划提醒】…在这一轮的回复里把它原样告诉用户…」；委托和子 agent 的报告（`:582`、`:617`、`:702`）、语音插话（`:550`）也一样。
     - 和原来那条「Runtime steering input」行（`RuntimeSteering.kt:24`）重复。
     - 手机上这一轮会从这里被劈成两半（`PhoneTimeline.kt:91`）。
   - 修法：Bothy 发插话时按请求 id 记下这是哪一类、要显示什么文字。投影时，内部插话不画用户气泡，用户自己的插话画显示用的文字。
2. **✔ 最终答案里又出现了「TTS:」开头的语音进度行。**
   - 位置：`ThreadItemProjection.kt:10` 显示的是原文，没有经过 `shownReply()`（`runtime/ShownReply.kt:7`）；A2A 那条路（`A2aEvents.kt:24`）倒是经过了。
   - 后果：控制台原样显示这些行；悬浮窗会跳过以「TTS:」开头的回复（`OverlayHud.kt:232`、`OverlayParts.kt:161`），可能显示一条旧回复、丢掉选项。
   - 修法：最终答案（`phase == FINAL`）先经过 `shownReply()` 再显示。
3. **agent 看不到陪聊和空闲时的计划提醒了。**
   - 位置：`recordCompanionTurn` 只写进 Bothy 自己的会话存储；这份存储现在只给 Jev 用，主提示词拿到的是空的（`RuntimeBuilder.kt:179-180`、`RuntimePrompt.kt:44`）；SDK 恢复的历史里也没有这些轮。
   - 后果：计划提醒问「要导航去加油站吗？」，用户回一个「好」，模型不知道在答什么；而 `ActionGate` 还在读 Bothy 的存储，两边对「上一个问题是什么」的理解不一致。
   - 修法：只在最近一次对话不是 SDK 的轮次时（陪聊或提醒），把它作为这一轮的上下文带给模型，相当于只针对这两类恢复原来的回顾。`ActionGate` 和模型用同一个来源。如果想让 SDK 提供「往历史里补外部消息」的接口，那是公开 API 的改动，要先问 owner。
4. **A2A 加了令牌，但界面上看不到令牌。**（`A2aServerManager.kt:15,33-51`）令牌只存在 `noBackupFilesDir` 里，现有调用方只会收到 401。修法：在设置里显示令牌并能复制，README 里写一句怎么用。
5. **批准的行为变了。**
   - 位置：`RuntimeConfirmation.kt:15,24-29`。
   - 用户在「批准过的不再问」模式下点批准，传的是 `ONCE`，所以不会被记住。修法：这种情况传 `THREAD`。
   - SDK 拿 MCP 服务 id 或工具名当区域，一次批准会放行整个服务。区域要细到工具区域，这部分在 SDK 侧，和 4.2 的 REMEMBER 那条一起改。
   - 卡片文字变成了「要访问 web_fetch:example.com」。修法：Bothy 显示时把区域的前缀去掉。
6. **子 agent 遇到批准时没人能回应，白等 60 秒。**
   - 位置：子 agent 用默认的 `ASK`（`RuntimeTurnRequest.kt:18`、`AppSubagents.kt:215`）；在「全部允许」之外的模式下，子 agent 的 `web_fetch` 要等满 60 秒，连「全部拒绝」也一样。
   - 修法：SDK 侧让子 agent 沿用父会话的批准策略；没法问人的批准立刻拒绝。Bothy 侧传入当前的模式。
7. **丢了几样东西，要补回来。**
   - 工具失败的提示条：`appendRuntimeResult` 现在没人调用，而手机靠它判断这一轮失败（`PhoneTimeline.kt:102`）。改成从 `TurnResult` 或 `FAILED` 状态的工具条目推出来。
   - 看板上的执行输出、工作区、搜索和网络状态。
   - A2A 的进度更新（搜索、网络、重试、压缩、预算）。另外，转发出去的提示是 SDK 的英文原文，比如「Retry 1 in 500 ms」，要换成 Bothy 自己的说法。
   - 子 agent 失败或被取消时，token 用量没被计入（`AppSubagents.kt:221`）。
   - 工具卡片的编号显示成 `turn:tool:call_x`。
8. **调试页的回放核对失效了。**（`RunEvidence.kt:45-53`）「公开」和「原始」两份记录来自同一个工具调用事件，`RunReplayVerifier` 永远不会报不一致。修法：改成拿条目和诊断接口里的事件日志比，或者干脆删掉这个核对。
9. **`traceRedactor` 关掉了电话号码和位置过滤。** 按第 2 节第 3 问的决定改。

## 6. Bothy 清理和提交

- **删掉没用的代码。**
  - 空文件 `ConfirmationDomains.kt`。
  - `RuntimeRunScope.kt:154-181` 里没用的 `confirmationDomain` / `confirmedDomains` 参数。
  - `RuntimeSessionRunner.kt`：第 586 行的 `activateSelectedRun = Unit`；第 176 行没用的 `requestId`；第 499 行注释里提到的 `ThreadEvent.TokenUsageDelta`，这个类型并不存在。
  - `RuntimePrompt.kt:29,44` 的 `previousExchange` 和 `replyContext`；`RuntimeBuilder.kt:159` 那句「the recap」的注释。
  - `TranscriptStore` 里的 `appendRuntimeResult`、`appendThinkingDelta`、`appendAssistantDelta`、`appendConfirmationRequired`、`toolIssuesForCurrentTurn`。
  - `Transcript.kt` 的 `parseToolResultStatus`、`parseRawToolCallId`，`RuntimeTimeline.kt:45` 的 `parseRawToolName`。
  - `SessionTurnRecorder.kt:12,174-182`、`RunEvidence.kt:5,84-95`、`McpProgressiveEvidence.kt:52,128-134`，以及 `RunReplayVerifier.kt:62` 里提到 `NativeToolCallStarted` 的地方。
  - `RawToolTranscriptHookTest.kt` 现在只测 `redactToolPayload`，改个名字。
- **整理提交。**
  - 两个 `chore(checkpoint)` 提交合成一个，按 Bothy 的格式写完整的说明和正文。提交者用 NoHarry；Co-Authored-By 行按你自己的模型写。
  - `harness/trace/2026-11-06-sdk-thread-api-migration.md` 会是这个公开仓库里第一个按日期命名的 trace 文件，里面还写着「不推送」。推荐不提交它。
- **依赖版本。** `gradle.properties:11` 还是 `agentRuntimeVersion=0.4.0`，0.5.0 发布后再改成 0.5.0，这是计划里的最后一步。

## 7. 修完以后怎么验证

1. **SDK**：
   - `./gradlew -q apiDump`，然后 `./gradlew -q --continue ciCheck`，全过。
   - 记录 dump 的实际行数，对照 4.1 第 9 条。
   - 4.2、4.3 里写了「测试」的每一条，都要有对应的测试。
2. **Bothy**：在 `~/project/bothy-api` 里从零编（`--rerun-tasks`），再跑一遍 validate。
3. **平板**（装包前先告诉 owner，装完恢复无障碍服务和录音权限，命令在交接文档第 5 节）。设计稿第 10 节的 8 项都要走一遍，另外**必须在 Bothy 界面上**看这几项，上次都没覆盖到：
   - 批准卡片在每种模式下的表现，包括跑到一半切换模式；拒绝以后的表现。
   - 回答 `ask`；回答 `ask_async`，开着多个问题时也试。
   - 跑着的时候插话：用户自己的插话显示正常，计划提醒和委托报告不再显示成用户气泡。
   - 控制台、悬浮窗、手机上的最终答案里都没有「TTS:」开头的行。
   - 陪聊或计划提醒之后，回答「好」，agent 能接上。
   - 杀掉进程再打开：会话列表和历史都在，工具卡片里的 JSON 正常。
   - A2A：不带令牌返回 401；从设置里复制令牌后请求返回 completed。
4. **CHANGELOG** 按 4.1 第 10 条补全。
5. **收尾**：
   1. SDK 分支快进到 main，推送。
   2. owner 点头后发布 0.5.0。
   3. Bothy 换成 Maven 上的 0.5.0，合成一个提交，评审后推送，平板再验一遍。
   4. 最后更新知识体系文章（交接文档第 10 节）。

## 8. 修复的建议顺序

1. 问 owner 第 2 节的四件事。
2. SDK 公开 API（4.1）。这一组决定冻结的样子，最先做。
3. SDK 行为（4.2）。
4. SDK 持久化和隐私（4.3）。
5. SDK 文档：CHANGELOG 和 KDoc。
6. Bothy（第 5、6 节）。从第 2 步起，每做完一组 SDK 改动，就拿 Bothy 编一遍。
7. 第 7 节的验证。

## 9. 确认没问题的（不要动）

- 正好 8 种 `ThreadEvent`，顺序规则写进了文档注释；条目有完整的结束事件，结束之后不会再有事件。
- 条目种类都有：`AgentMessage.phase`；`ToolCall` 带 `server`、`network`、`approvedBy`；`Approval`；`Question(async)`。
- `AgentThread` 的方法齐全，加了 `steer(text, answering)`，这是个改进；`RespondResult` 是枚举，Java 也好用。
- 旧方法、`AgentRequest`、`AgentEvent`、`OpenConfusion`、`RuntimeBlocker`、插件系统都收进了内部，协议请求删了。
- `agent-api` 模块和 `cc.noharry.agent` / `.spi(.model/.mcp)` 的包结构到位了；kernel-core 没了，可选模块只依赖 `agent-api`。
- 四项破坏性改动都做了：`ServerSideSearchCapability` 挪到了 `cc.noharry.agent.model`；`ModelMessage.reasoningContent` 在 OpenAI 和 Anthropic 客户端以及存盘历史里都接通了；不给模型 `build()` 会报错；默认值是中性的（没有内置话题，结束语检查只认英文，没有必应 RSS 那行）。
- MCP 区域规则认得带 `_` 的服务 id。hook 用的是 `onTurnStarted` / `onTurnFinished(TurnResult)`。
- 在「全部允许」下，hook 的拒绝仍然优先：批准之后执行前还会再跑一遍 hook。
- 回应、过期、取消三者之间没有竞争：重复回应得到 `ALREADY_ANSWERED`；一轮结束后再回应得到 `NOT_FOUND` 或 `ALREADY_ANSWERED`；在收集事件的代码里回应不会死锁。
- `ask_async` 的回答不会漏到下一轮；子 agent 不能提问。
- 图片能送到 `ModelMessage.images`，模型不收图时以 `IMAGE_INPUT_UNSUPPORTED` 结束这一轮。
- SQLite 的结构还是 v5，0.5.0 打开 0.4.0 的数据库不会崩，也不会清空。
- Bothy 的前端协议（agent-app-protocol、bothy-protocol）没有改动；没有代码调用已删除的 SDK API；旧默认值都显式写上了；A2A 加了鉴权；通知渠道有了名字。
