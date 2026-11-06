# Security

## Reporting a vulnerability

Report it privately on GitHub: this repository's **Security** tab, then **Report a vulnerability**. Please don't open
a public issue for it. You'll get an answer within a week; the fix ships in a patch release of the latest minor
version, and the advisory credits you unless you'd rather it didn't.

Only the latest minor release gets security fixes while the project is 0.x.

## What the host decides

An agent carries out tool calls a model writes, inside your app's process. The runtime gives you the controls; which
actions the model may take is your decision.

- **Tools.** The model may call every tool you register and every tool a connected MCP server offers. Register what
  the agent needs, and put anything that spends money, sends messages or changes data behind a hook (`hook(…)`) or a
  policy rule (`policyRule`, `policyConfig`) that asks the user or refuses.
- **Text from outside.** Web pages, tool results, MCP servers, other agents and other apps' screens all put text in
  front of the model, and a model may follow instructions written there. The runtime cannot tell; guard what the agent
  can do, not what it reads. Everything the agent reads is also sent to your model endpoint.
- **Files.** The file tools reach only the runtime's workspace and the folders you mount, and paths that escape them
  are refused. Keep keys and other secrets out of those folders.
- **Network.** `web_fetch` refuses private and loopback addresses, and by default asks before the first request to each
  new host in a run (`networkConfirmationPolicy`). MCP servers and model endpoints are reached as you configure them.
- **Android Use** (`agent-android-use`) reads every app's screen through an accessibility service, sends what it sees
  (text and screenshots) to the model, and taps and types in any app. Turn it on only where that is the point, keep
  the status and navigation bars protected (`protectSystemBars`), and gate its acting tools with a hook.
- **`exec_js`** (`agent-exec-js`) runs scripts in QuickJS with no file or network access of their own; each tool a
  script calls goes through the same hooks, policy and confirmation as the model's, within the engine's tool-call,
  memory and time limits.
- **The A2A server** listens on the loopback address, where every app on the device can reach it. Give it an
  authorizer (`A2aServerConfig(authorizer = A2aAuthorizer.bearer(token))`); it listens on another address only with
  one, behind an `https` public URL.
- **Results, events and trace summaries** hide keys and tokens, and by default phone numbers and coordinates
  (`traceRedactor`). This catches common shapes, not everything, and the session event log and session store keep the
  conversation as it was: treat them, and logs, as sensitive.
