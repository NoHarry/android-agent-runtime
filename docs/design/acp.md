# ACP 支持（agent-acp 模块）

> 状态：已实现（2026-11-09），随 0.5.0 一起发布。
> 规范：Agent Client Protocol，协议版本 1（https://agentclientprotocol.com）。写这份文档时查过的页面：overview、initialization、session-setup、prompt-turn、tool-calls、content、agent-plan、session-modes、transports。

## 1. 目标

让编辑器（Zed 等）和其他 ACP 客户端直接驱动运行时的会话，不需要为每个客户端单独写适配。做成可选模块，只依赖公开 API（`agent-api`），和 `agent-a2a` 同一级。

不做的事：

- 不用客户端的文件系统、终端和 MCP 服务器。agent 用运行时自己的工作区和工具。
- 不做鉴权。stdio 由启动它的客户端独占；宿主自己开的 socket 由宿主负责只让可信的一方连上。

## 2. 对外 API

```kotlin
val agent = AcpAgent(runtime, AcpAgentConfig(name = "my-agent", version = "1.0"))
agent.serveStdio()                      // 编辑器把 agent 当子进程启动
agent.serve(StreamAcpTransport(i, o))   // 任意一对流，比如 Android 的 LocalSocket
```

- `AcpAgent(runtime, config)`：`serve(transport)` 服务一条连接，直到对方关闭；这时还在跑的轮次会被中断。一个 `AcpAgent` 可以同时服务多条连接。
- `AcpTransport`：`receive()` / `send()`，每行一条 JSON-RPC 消息。`StreamAcpTransport` 是基于一对流的实现。
- `AcpAgentConfig`：
  - `name` / `title` / `version`：`initialize` 里报给客户端的 agent 信息；
  - `threadOptions(cwd)`：新会话的线程默认值（模型、工作区、说明、批准策略）；
  - `messages`：给客户端显示的几个词（批准选项、模式名），默认英文，宿主可以换成自己的语言。

## 3. 对应关系

| ACP | 运行时 |
|---|---|
| `initialize` | 协议版本 1；能力：`loadSession`、图片和内嵌文本资源 |
| `authenticate` | 空实现，不需要鉴权 |
| `session/new` | `startThread`；会话 id 就是线程 id；返回四个模式 |
| `session/load` | `resumeThread`，再把 `items()` 作为 `session/update` 重放一遍；没有这个会话时返回 -32002 |
| `session/prompt` | `runStreamed`；轮次结束后回 `stopReason` |
| `session/cancel` | `interrupt()`，这一轮的 `session/prompt` 以 `cancelled` 结束 |
| `session/set_mode` | 批准策略：`ask`、`remember`、`approve_all`、`deny_all`。正在跑的轮次立刻生效 |
| `session/request_permission` | `Approval` 条目。选项：允许一次、总是允许（记到会话）、拒绝 |

条目到 `session/update`：

| 条目 | 更新 |
|---|---|
| `AgentMessage` | `agent_message_chunk`，`messageId` 是条目 id，增量逐块发 |
| `Reasoning` | `agent_thought_chunk` |
| `UserMessage`（插话，或重放历史时） | `user_message_chunk` |
| `ToolCall` | 开始时 `tool_call`，之后 `tool_call_update`，带原始参数和结果 |
| `ToolCall(update_todo)` | `plan`，每次发完整列表 |
| `WebSearch`、`FileChange` | 一条已完成的 `tool_call`（kind 分别是 search、edit） |
| `Question` | `agent_message_chunk`；模型已经把问题当消息说过就不重复 |
| `finish`、`ask` 这类工具 | 不显示：它们的效果就是答案或提问 |
| `Notice`、`Error` | 不发。失败由 `session/prompt` 的错误响应带回 |

`stopReason`：完成或停下来提问是 `end_turn`；被取消是 `cancelled`；轮数预算用完是 `max_turn_requests`；其他失败回 JSON-RPC 错误（-32603，消息里带错误码）。

提问：`ask` 结束这一轮，客户端的下一条 prompt 就是回答（运行时自己绑定）。

## 4. 和规范不一致、或没做的地方

- **传输**：规范目前只定义了 stdio，HTTP 还在起草。设备上的 agent 不是子进程，所以用规范允许的自定义传输：同样的消息走任意流。编辑器要连设备上的 agent，需要一个把 stdio 转到 socket 的小桥。
- **MCP 服务器**：规范要求 agent 支持客户端在 `session/new` 里给的 stdio MCP 服务器。这里忽略 `mcpServers`，工具由宿主在构建运行时的时候配置。
- **`session/load` 不返回当前模式**：公开 API 读不到线程存下来的批准策略。`session/set_mode` 仍然可用。
- **插话**：ACP 没有对应的方法。一轮进行中再发 `session/prompt` 会排在后面，成为下一轮。`ask_async` 的提问因此收不到回答，轮次结束时作废。
- **权限请求撤不回来**：批准过期或被别处回应后，客户端可能还显示着请求；它之后的回答会被忽略。
- **音频输入**：拒绝（-32602）。
- **会话配置项（session config options）**：规范打算用它取代模式，这一版只做了模式。
- **提示类更新（notice、compaction）**：没发。

## 5. 验证

- `AcpAgentTest`：在内存连接上，用真实运行时加脚本模型走完整流程：初始化、批准后工具执行、拒绝、模式即策略、等权限时取消、提问后回答、加载会话重放历史、各种错误码。
- `samples/acp-jvm`：能被编辑器直接启动的 stdio agent。**还没有用真实的编辑器连过**，这是剩下的验证。

## 6. 后续

- 用 Zed 实测 `samples/acp-jvm`，按实际表现调整标题、kind 和权限选项。
- Android 上的 socket 传输示例，加上 stdio 到 socket 的桥。
- 会话配置项；`session/list`、`session/resume`、`session/close`（规范里有，这一版没做）。
- 插话：等规范有对应方法，或者用扩展方法。
