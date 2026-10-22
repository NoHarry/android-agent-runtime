# Android Agent Runtime: agent map

An embeddable agent runtime for Android and the JVM. Gradle project at the repository root.

## Layout

| Path | What |
|---|---|
| `agent-kernel-api`, `agent-kernel-core` | Kernel: contracts and the agent loop (JVM) |
| `agent-vfs`, `agent-runtime` | The workspace VFS and the runtime facade (JVM): model clients, MCP (`cc.noharry.agent.mcp`), `functionTools`, sessions, hooks |
| `agent-runtime-android` | Android layer: SQLite sessions, app-private event log, `AndroidAgentRuntime.builder` |
| `agent-minimal` | Minimal profile on the kernel |
| `agent-vfs-saf`, `agent-exec-js`, `agent-device-capabilities`, `agent-android-use`, `agent-voice`, `agent-a2a-*` | Optional modules (Android) |
| `agent-bom` | Version alignment |
| `samples/` | `quickstart-jvm`, `quickstart-android` (not published) |
| `buildSrc/` | `SourceRulesTask` |
| `tools/` | Python helpers and fixtures; `agent-runtime` tests read `tools/fixtures` |

## Rules

- Packages are `cc.noharry.agent.*`; Maven coordinates `cc.noharry:<module>`.
- No Android API in the JVM modules. Optional modules depend at most on `agent-kernel-api`; the runtime depends on none.
- First-party providers declare `providerConcurrency` explicitly.
- Files at most 1000 lines, functions at most 50 (`checkSourceRules`).
- Public API changes in the JVM modules need `./gradlew apiDump` and a `CHANGELOG.md` note when breaking.
- Host-visible messages default to English and are replaceable; `agent-voice` is Mandarin by design.
- Never commit `local.properties`, keys or build output.

## Commands

```bash
./gradlew ciCheck                     # source rules, API check, unit tests, sample builds
./gradlew apiDump                     # after an intended public API change
./gradlew publishToMavenLocal         # try the artifacts in another project
./gradlew :agent-runtime-android:connectedDebugAndroidTest   # with a device
```
