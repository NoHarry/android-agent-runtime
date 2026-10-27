# Android Agent Runtime: agent map

An embeddable agent runtime for Android and the JVM. Gradle project at the repository root.

## Layout

| Path | What |
|---|---|
| `agent-kernel-api`, `agent-kernel-core` | Kernel: contracts and the agent loop (JVM) |
| `agent-runtime` | The runtime facade (JVM): model clients, MCP (`cc.noharry.agent.mcp`), the workspace VFS (`cc.noharry.agent.vfs`), `functionTools`, sessions, hooks |
| `agent-runtime-android` | Android layer: SQLite sessions, app-private event log, `AndroidAgentRuntime.builder`, SAF mounts (`cc.noharry.agent.vfs.saf`) |
| `agent-exec-js`, `agent-device-capabilities`, `agent-android-use`, `agent-a2a`, `agent-a2a-client-official` | Optional modules (Android) |
| `agent-bom` | Version alignment |
| `samples/` | `quickstart-jvm`, `quickstart-android`, `minimal` (not published) |
| `buildSrc/` | `SourceRulesTask` |
| `tools/` | Python helpers and fixtures; `agent-runtime` tests read `tools/fixtures` |

## Rules

- Packages are `cc.noharry.agent.*`; Maven coordinates `cc.noharry:<module>`.
- No Android API in the JVM modules. Optional modules depend at most on `agent-kernel-api`; the runtime depends on none.
- First-party providers declare `providerConcurrency` explicitly.
- Files at most 1000 lines, functions at most 50 (`checkSourceRules`).
- Public API changes in the JVM modules need `./gradlew apiDump` and a `CHANGELOG.md` note when breaking.
- Host-visible messages default to English and are replaceable.
- Never commit `local.properties`, keys or build output.

## Commands

```bash
./gradlew ciCheck                     # source rules, API check, unit tests, sample builds
./gradlew apiDump                     # after an intended public API change
./gradlew publishToMavenLocal         # try the artifacts in another project
./gradlew :agent-runtime-android:connectedDebugAndroidTest   # with a device
```
