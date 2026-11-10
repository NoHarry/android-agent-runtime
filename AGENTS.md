# Android Agent Runtime: agent map

An embeddable agent runtime for Android and the JVM. Gradle project at the repository root.

## Layout

| Path | What |
|---|---|
| `agent-api` | Stable conversation contracts and extension interfaces (JVM) |
| `agent-runtime` | The runtime facade and internal agent loop (JVM): model clients, MCP (`cc.noharry.agent.mcp`), the workspace VFS (`cc.noharry.agent.vfs`), `functionTools`, sessions, hooks |
| `agent-runtime-android` | Android layer: SQLite sessions, app-private event log, `AndroidAgentRuntime.builder`, SAF mounts (`cc.noharry.agent.vfs.saf`) |
| `agent-a2a` | Optional module (JVM): A2A client, direct transport and server |
| `agent-acp` | Optional module (JVM): the Agent Client Protocol (ACP) over the thread API; design in `docs/design/acp.md` |
| `agent-exec-js`, `agent-device-capabilities`, `agent-android-use`, `agent-a2a-client-official` | Optional modules (Android) |
| `agent-bom` | Version alignment |
| `samples/` | `quickstart-jvm`, `acp-jvm`, `quickstart-android`, `minimal`, `extensions` (offline; its test runs in CI) (not published) |
| `buildSrc/` | `SourceRulesTask` |
| `tools/` | Python helpers and fixtures; `agent-runtime` tests read `tools/fixtures` |
| `docs/design/` | Designs for upcoming work; read the one that matches your task before changing the public API (`public-api-redesign.md`: the Thread / Turn / Item API replacing the current one in 0.5.0; section 9.3 approved on 2026-11-06; start from `public-api-redesign-handoff.md`, then `public-api-redesign-review.md` (fixes required before releasing 0.5.0); `app-server-split.md`: moving the app server here from the Bothy repository, its plugin kernel; `plugin-packages.md`: plugins a person downloads, declarative ones and tools written as scripts, as designed and as built) |

## Rules

- Packages are `cc.noharry.agent.*`; Maven coordinates `cc.noharry:<module>`.
- No Android API in the JVM modules. Optional modules depend at most on `agent-api`; the runtime depends on none.
- First-party providers declare `providerConcurrency` explicitly.
- Files at most 1000 lines, functions at most 50 (`checkSourceRules`).
- Public API changes need `./gradlew apiDump` (every published module but the BOM has an `api/*.api`) and a
  `CHANGELOG.md` note when breaking.
- Host-visible messages default to English and are replaceable.
- Never commit `local.properties`, keys or build output.

## Commands

```bash
./gradlew ciCheck                     # source rules, API check, unit tests, sample builds
./gradlew apiDump                     # after an intended public API change
./gradlew publishToMavenLocal         # try the artifacts in another project
./gradlew :agent-runtime-android:connectedDebugAndroidTest   # with a device
```
