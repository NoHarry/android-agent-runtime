# 对外 API 重设计：交接

> 写于 2026-11-06，给接手的模型。先读完这份，再读同目录的 `public-api-redesign.md`（设计稿）。
> 和 owner 交流用中文。

> 接手更新（2026-11-06）：owner 已在接手会话确认「全部按 9.3 推荐实施」。下文关于 9.2 / 9.3 等确认的记录是交接时的状态，不再是开工阻塞；Maven Central 发布仍需单独授权。实现使用 `codex/api-redesign` 分支和独立 worktree。

## 1. 一句话

0.5.0 把运行时（`android-agent-runtime`）的对外 API 换成 Codex 那样的 Thread / Turn / Item，同时把 Bothy 一次迁完，之后两边都不再做结构性迁移。

## 2. 现在到哪了

- **设计稿写完了**：`docs/design/public-api-redesign.md`。代码一行还没动。
- **owner 已定**（设计稿 9.1）：
  - 0.5.0 直接用新 API 替换旧 API，不留过渡版本；0.5.0 等这些做完再发。
  - kernel-core 并进 agent-runtime，改成 `internal`，不再单独发布。
- **还没定**（设计稿 9.2、9.3）：每项都附了推荐，9.3 是「运行时加 Bothy 一步到位」的整体推荐。owner 最后看到的就是 9.3，还没回复。
- **你的第一件事**：问 owner「9.2 和 9.3 是否都按推荐」。没得到确认之前不要写实现代码。
- **未提交的改动**（都在 SDK 仓的 main 工作区）：
  - `docs/design/public-api-redesign.md`
  - `docs/design/public-api-redesign-handoff.md`（本文件）
  - `AGENTS.md` 里新加的 `docs/design/` 那一行

  不要 stash，不要切分支把它们弄丢。开工时的处理办法见第 7 节。
- **在线文档已作废**：之前在 Claude Docs 上开过一份在线稿（https://claude.ai/artifact/G5PfvMfxaR4nVS9Dx2KLcJ），只写了前两节。owner 说设计文档放仓库、不用在线文档。不要再更新它；要不要删，问 owner。

## 3. 仓库和位置

| 什么 | 在哪 |
|---|---|
| 运行时 SDK | `~/project/android-agent-runtime`，GitHub `NoHarry/android-agent-runtime`，main = `d2f1c49`，版本 `0.5.0-SNAPSHOT`。Maven Central 上最新的是 0.4.0 |
| Bothy | `~/project/bothy-server`，GitHub `NoHarry/bothy`，main = `c6502c86`，工作区干净。Android 工程在 `workspace/apps/android/`。`~/project/bothy` 是旧克隆，不要用 |
| Bothy 怎么用 SDK | `workspace/apps/android/settings.gradle.kts` 用 `includeBuild` 编 SDK 源码，默认路径是 `../../../../android-agent-runtime`（即 `~/project/android-agent-runtime`），可以用 `-PagentRuntimeDir=<路径>` 改。SDK 不在旁边时用 Maven 上的版本 |
| Codex 参考源码 | `~/project/codex/codex`（`ce0323f2f2`，2026-10-17）。TS SDK 在 `sdk/typescript/src/`（`thread.ts`、`items.ts`、`events.ts`、`threadOptions.ts`）；app-server 协议在 `codex-rs/app-server-protocol/src/protocol/common.rs`；提问工具在 `codex-rs/core/src/tools/handlers/request_user_input*.rs` |
| 交接用的脚本 | `~/.bothy/handoff/tools/`：`turn_probe.py`（走 app-server 协议跑一轮）、`repin4.py`（文章链接重新固定）、`article-brief-repin5.md`（上次更新文章时给子 agent 的说明，可当模板） |
| Bothy 的老交接 | `~/.bothy/handoff/HANDOFF.md`。写于 10-21，路径有些过时，但规矩和平板操作还能参考 |
| 知识体系文章 | `/Users/hanyulou/Documents/my_ob/Doc/AI/agent知识体系/`（Obsidian，00–29、03.1、06.1、27.1 和附录）。链接现在固定在运行时 `3944c51`、Bothy `c6502c86` |

## 4. 规矩（owner 定的，必须遵守）

- **身份**：提交用 NoHarry 身份（两个仓的 repo 级 git config 已经设好，提交时最好仍显式带上 `GIT_AUTHOR_NAME=NoHarry GIT_AUTHOR_EMAIL=noharry@users.noreply.github.com GIT_COMMITTER_NAME=NoHarry GIT_COMMITTER_EMAIL=noharry@users.noreply.github.com`）。不能用公司的 git 身份。提交信息末尾加你自己的 Co-Authored-By 行。
- **代理**：git 和会调用 git 的脚本、hook，都要去掉代理变量（`env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u all_proxy`）。推送量大时调大 `http.postBuffer`。
- **推送**：推送前先做一次独立评审（另起评审者，只给它代码、不给你的结论），评审过了再推。owner 对这个计划授权过「改完评审了直接推」。force push 要 owner 明确同意。
- **发布**：发布到 Maven Central 必须 owner 点头。owner 说过先别发。
- **Bothy 用新 API 的时机**：新 API 没发布到 Maven 之前，Bothy 不能推送用到它的代码。本地对着 SDK 源码编、在平板上验证可以。
- **密钥**：`local.properties` 里的密钥和 `~/.claude.json` 里的 Stitch key 永远不打印、不提交。
- **平板**：
  - 往 owner 的平板装包前先说一声：装包会立刻杀掉 App，看着像崩溃，还会让无障碍服务掉线。
  - 装完要恢复无障碍服务和录音权限（命令见第 5 节）。
  - 不卸载 App；本地打的 release APK 不外发。
- **验证要快**：只跑单测、validate，再在平板上做针对改动的短检查。长时间模拟和连续演示要 owner 点名才跑。
- **推送前从零编译**：改了公开 API 以后，推送前 Bothy 要从零编一遍（`--rerun-tasks`）。Bothy 的 validate 是增量编译，曾经掩盖过 main 上的编译错误。
- **文章写法**：知识体系文章只讲系统现在怎么工作，不写修复过程，正文里不写提交号；版本说法用「运行时 main 上（还没发布）」，发布后改成版本号。大改前等 owner 看过。

## 5. 怎么验证

**SDK**（在 SDK 仓里，先去掉代理变量）：

```bash
./gradlew -q apiDump                 # 改了公开 API 以后
./gradlew -q --continue ciCheck      # 源文件规则、API dump、animal-sniffer、lint、dokka、全部测试（含 samples/extensions）
./gradlew :agent-exec-js:connectedDebugAndroidTest :agent-runtime-android:connectedDebugAndroidTest   # 接着平板时（8 个、3 个测试）
```

GitHub CI（`.github/workflows/ci.yml`）有三个 job：`check`、`published`（`publishToMavenLocal` 之后，用 `-PsamplesFromMavenLocal` 跑 `:samples:extensions:test :samples:extensions:run`）和 `secrets`。推送后用 `gh run list` / `gh run watch` 看结果。

**Bothy**：

```bash
cd ~/project/bothy-server && DEVELOPER_DIR=/Library/Developer/CommandLineTools PATH=$HOME/.bothy/venv/bin:$PATH python scripts/validate.py
```

本机的系统 Python 缺 PyYAML 和 jsonschema，所以要用 `~/.bothy/venv`。pre-push hook 会真跑一遍 validate。

**平板**（设备 `HA2H3Y7F`，Bothy 包名 `cc.noharry.bothy`）：

- 装完包后恢复：

  ```bash
  adb -s HA2H3Y7F shell settings put secure enabled_accessibility_services cc.noharry.bothy/cc.noharry.agent.androiduse.AndroidUseAccessibilityService
  adb -s HA2H3Y7F shell settings put secure accessibility_enabled 1
  adb -s HA2H3Y7F shell pm grant cc.noharry.bothy android.permission.RECORD_AUDIO
  ```

- 走 app-server 协议跑一轮：

  ```bash
  adb -s HA2H3Y7F forward tcp:8793 localabstract:bothy
  python3 ~/.bothy/handoff/tools/turn_probe.py "现在几点" 120
  ```

- A2A：

  ```bash
  adb -s HA2H3Y7F forward tcp:8766 tcp:8766
  curl --noproxy '*' -s -m 90 http://127.0.0.1:8766/a2a -H 'content-type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"message/send","params":{"message":{"role":"user","kind":"message","messageId":"probe-1","parts":[{"kind":"text","text":"一句话告诉我今天星期几"}]},"configuration":{"blocking":true}}}'
  ```

  返回的 `status.state` 应该是 `completed`。
- 设计稿第 10 节列了这次要走的 8 项平板检查。

## 6. 设计里用到的代码事实（基于 `d2f1c49`，动手前自己再核对一遍）

SDK：

- `agent-kernel-api/.../kernel/api/AgentRuntime.kt`：`AgentRuntime` 的 21 个方法、`AgentEvent` 的 23 种事件、`AgentRequest`（第 151 行）都在这个文件里。
- **批准**：`agent-runtime/.../runtime/DefaultAgentRuntime.kt` 发出 `ConfirmationRequired`（约第 704 行和第 788 行）之后，`run()` 的 Flow 就结束了，见 `ConfirmationContextResumeTest`。`resumeConfirmation`（约第 306 行）进入 `run/RuntimeControlResumeCoordinator.kt` 的 `resumeApprovedConfirmation`（约第 88 行），在调用里把后半段跑完，只返回 `Accepted`。暂停中的工具调用存在 `pendingToolUseController` 里。默认超时 60 秒（`AgentRuntimeBuilderRecords.kt` 第 8 行）。
- **`ask`**：工具定义在 `agent-kernel-core/.../core/tools/NativeToolDefinitions.kt` 的 `TERMINAL_TOOLS`。处理在 `core/loop/NativeRoundRunner.kt` 的 `askToolCall`（约第 709 行），返回 `RoundOutcome.NeedsUserInput`。之后 `DefaultAgentRuntime` 的 `emitWaitingUserInput` 让 run 进入 WAITING_USER_INPUT。
- **`ask_async`**：在 `core/loop/NativeUserInteraction.kt`（`ASK_ASYNC`、`INTERACTION_TOOLS`）。回答走 `postUserInput`。
- **子 agent 不能提问**：`agent-runtime/.../run/NativeLoopFactory.kt` 约第 310 行，`userReachable = !childRun`。
- **工具事件被换成占位**：`core/trace/PublicVisibilityRedaction.kt` 的 `recordStarted`（第 156 行）、`recordFinished`（第 165 行）和 `redactedPublicJson`（第 305 行）。过滤规则在 `core/trace/TraceSecurity.kt`。
- **没配模型**：`core/loop/NativeToolLoop.kt` 约第 132 行，每次运行都以「没配置模型」结束，`build()` 本身不报错。
- **会话历史只在内存里**：`agent-runtime/.../runtime/SessionHistory.kt`，KDoc 写着 Memory only。会话存储的接口在 `core/state/SessionStore.kt`，Android 上的 SQLite 实现在 agent-runtime-android。
- **协议请求**：唯一会产生它的是 `AgentRuntimeBuilder.kt` 里 internal 的 `protocolRequestOnRun`（约第 270 行）。
- **依赖关系**：依赖 kernel-core 的只有 agent-runtime 和 `samples/minimal`（它用 `KernelRuntimeBuilder`）。kernel-core 有 38 个测试文件。`desugaringModules` 在根 `build.gradle.kts` 第 165 行，BOM 在 `agent-bom/build.gradle.kts`。

Bothy（以下路径都在 `~/project/bothy-server/workspace/apps/android/` 下）：

- 协议定义：`agent-app-protocol/src/main/kotlin/cc/noharry/agent/appserver/protocol/Methods.kt` 和 `Conversation.kt`（`ConversationItem`）。
- `agent-app-server/src/main/java/cc/noharry/agent/appserver/` 下要改的文件：
  - `runtime/RuntimeRunCollection.kt`
  - `runtime/RuntimeSessionRunner.kt`
  - `session/SessionTurnRecorder.kt`
  - `session/TranscriptStore.kt`
  - `runtime/RuntimeConfirmation.kt`
  - `runtime/ConfirmationDomains.kt`
  - `runtime/FollowUpReply.kt`
  - `runtime/PreviousExchange.kt`
  - `runtime/RawToolTranscriptHook.kt`
  - `runtime/ToolTranscriptRedactor.kt`
  - `runtime/RuntimeSteering.kt`
  - `runtime/RuntimeInput.kt`
  - `a2a/A2aEvents.kt`
  - `a2a/AppA2aRequestHandler.kt`
  - `evidence/RunEvidence.kt`
- 直接 import kernel-core 的 8 个文件，名单见设计稿 2.3。
- 用到 `ServerSideSearchCapability` 的 4 个文件：`RuntimeBuilder.kt`、`HostedSearchValidation.kt`、`ProviderConfig.kt`、`OnDeviceHost.kt`。

## 7. 开工怎么起步（owner 确认之后）

1. **把设计稿放到新分支上，main 工作区保持干净。** Bothy 本地编的是 `~/project/android-agent-runtime` 里的源码，这个工作区要一直停在 main。

   ```bash
   cd ~/project/android-agent-runtime
   git switch -c api-redesign          # 未提交的改动会跟过来
   git add docs/design AGENTS.md && git commit -m "docs: design for the 0.5.0 public API"
   git switch main
   git worktree add ../android-agent-runtime-api api-redesign
   ```

2. **Bothy 也开一个 worktree**，编译时指向 SDK 分支：

   ```bash
   git -C ~/project/bothy-server worktree add ../bothy-api -b api-redesign
   # 在 ~/project/bothy-api/workspace/apps/android 下的 gradle 命令都带：
   #   -PagentRuntimeDir=$HOME/project/android-agent-runtime-api
   ```

   validate.py 能不能把这个属性传下去，先看脚本。传不下去，就在这个 worktree 的 `gradle.properties` 里临时加一行 `agentRuntimeDir=...`，不要提交。
3. **按设计稿第 10 节逐步做**。如果 9.3 被接受，9.3 第 3、4、5 项插在第 3、4 步旁边做。每一步都：
   - 跑 `apiDump` 和 `ciCheck`；
   - 拿 Bothy 分支编一遍；
   - 独立评审后推到 `api-redesign` 分支。
4. **全部做完以后**：
   1. 用 SDK 分支加 Bothy 分支的组合，在平板上走一遍检查。
   2. 合进 SDK main，写 CHANGELOG 的 0.5.0 一节（破坏性改动加迁移对照）。
   3. 等 owner 点头后发布。
   4. Bothy 改成依赖 Maven 上的 0.5.0，评审后推送，平板再验一遍。

## 8. 坑

- **Kotlin `jvmDefault`**：保持 `ENABLE`（保留 `DefaultImpls`），不能改成 `NO_COMPATIBILITY`。Java 宿主靠它只实现自己要改的方法，`JavaHostDefaultsTest` 守着这一点。
- **`@InternalAgentRuntimeApi`**：它在 kernel-api 里（RequiresOptIn ERROR，BINARY），列在 `apiValidation.nonPublicMarkers` 里，所以不进 API dump。测试编译默认 opt in，agent-a2a 和 agent-a2a-client-official 两个模块除外。带这个标记的声明在 KDoc 里写 `@suppress`，dokka 才会跳过。
- **Android 模块的 API dump**：buildSrc 里用 BCV 自己的 `KotlinApiBuildTask` 加独立 classpath 生成。Kotlin 自带的 abiValidation 在 AGP 9 内置 Kotlin 下会生成空 dump；把 BCV/ASM 直接放上 buildSrc classpath，又会盖掉 AGP 自带的 ASM 9.9。
- **兼容 Android 6**：animal-sniffer 加 Gummy Bears 0.16.0 检查。kernel 和 runtime 用 api-23 coreLib2Nio 加 api-26，其余模块用 api-23。`RuntimeException(msg, cause, false, false)` 和 `Map.forEach` 都是 API 24 才有，在 Android 6 上会崩。
- **OkHttp**：`ReleaseHardeningTest` 不允许公开 API 里出现 OkHttp 类型；要给宿主配置，用 JDK 类型（超时、`java.net.Proxy`）。
- **源文件规则**：每个文件不超过 1000 行，每个函数不超过 50 行（`checkSourceRules`）。`SessionRunScheduler.kt` 正好 1000 行。
- **事件日志**：编码器拒收带 `sk-`、`Bearer ` 的文本，写交互事件时用 `appendEventSafely`，所以落盘的内容可能比现场看到的少。
- **提示词版本**：`BOOTSTRAP_PROMPT_VERSION` 现在是 v11，改了提示词要跟着升。
- **A2A 测试**：`A2aServer.close()` 会等监听线程结束（最多 1 秒）。原因是 Linux 上的 JDK 17 在 accept 阻塞时关 ServerSocket 会延后，以前因此 flaky 过。
- **调查结论要核对**：以前子 agent 的调查在细节上错过好几次。写进设计、文章或代码之前，自己对一遍源码。

## 9. 这次之外还挂着的事（问 owner 时可以一并提）

- 子 agent 必须有工作区才能用。
- 没有工作区时，`INACTIVE_BACKEND_TOOL` 的报错里还带着 `schemaPath`；没有工作区的运行时，提示词里也不列工具区域。
- 下面这些已经纳入 9.3，不用单独问：Bothy 的 A2A 服务端没有鉴权、通知渠道用的是默认名、`ToolDomainNaming` 不认带 `_` 的 server id、工具卡片遮号码。

## 10. 推送之后：更新知识体系文章

1. 先备份 vault。
2. 用 `~/.bothy/handoff/tools/repin4.py` 把链接重新固定到新的提交：

   ```bash
   python3 repin4.py <vault> <repo> <旧提交> <新提交> https://github.com/NoHarry/android-agent-runtime/blob/ [--write]
   ```

   Bothy 那边的基础地址换成 `https://github.com/NoHarry/bothy/blob/`。基础地址末尾必须带 `/`。先不加 `--write` 看一遍，标 `??` 的链接要手工核对。
3. 按新 API 改正文。可以分章交给子 agent，说明照 `article-brief-repin5.md` 的写法。
4. 文章的固定结构是：一句话、要解决的问题、通用原理、本项目怎么做、别人怎么做、取舍与坑、动手、自测题、我的疑问。
5. owner 会在最后统一看。
