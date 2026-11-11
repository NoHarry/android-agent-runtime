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
| `agent-app-protocol`, `agent-app-server` | The app server (experimental, published): its protocol (JVM) and the server with its plugin kernel (Android); host guide in `agent-app-server/README.md`. Its first-party plugins are source modules of the Bothy app, not here and not Maven artifacts (their source is published with Bothy's next release) |
| `samples/` | `quickstart-jvm`, `acp-jvm`, `quickstart-android`, `minimal`, `extensions` (offline; its test runs in CI), and the apps on the app server `agent-app-sample`, `-notes`, `-timer`, `-habits` (the last two tested offline) (not published) |
| `buildSrc/` | `SourceRulesTask`, `PackageRulesTask` and `ForbiddenDependenciesTask` (the app server's layering) |
| `tools/` | Python helpers and fixtures; `agent-runtime` tests read `tools/fixtures`; `agent-plugin` makes plugin packages, `app-server-api` lists the app server's API by level |
| `docs/design/` | Designs for upcoming work; read the one that matches your task before changing the public API (`public-api-redesign.md`: the Thread / Turn / Item API replacing the current one in 0.5.0; section 9.3 approved on 2026-11-06; start from `public-api-redesign-handoff.md`, then `public-api-redesign-review.md` (fixes required before releasing 0.5.0); `app-server-split.md`: the app server's design, what is published, its plugin kernel and where its first-party plugins live; `plugin-packages.md`: plugins a person downloads, their format, trust, permissions and tools written as scripts) |

## Rules

- Packages are `cc.noharry.agent.*`; Maven coordinates `cc.noharry:<module>`.
- The app server's core (`agent-app-protocol`, `agent-app-server`) names no plugin's package and carries none of
  `agent-a2a*`, `agent-android-use` or `agent-device-capabilities` (`checkAppServerLayering`). Its public
  API has three levels, listed in `agent-app-server/api/agent-app-server-levels.txt`: a host's, `@AppServerPluginApi`
  for plugin modules, `@InternalAppServerApi` for other modules' tests only; new declarations are `internal`. The
  app server's entry points carry `@ExperimentalAppServerApi`.
- No Android API in the JVM modules. Optional modules depend at most on `agent-api`; the runtime depends on none.
- First-party providers declare `providerConcurrency` explicitly.
- Files at most 1000 lines, functions at most 50 (`checkSourceRules`).
- Public API changes need `./gradlew apiDump` (every published module but the BOM has an `api/*.api`;
  `agent-app-server`'s levels need `python3`) and a `CHANGELOG.md` note when breaking.
- Host-visible messages default to English and are replaceable.
- Never commit `local.properties`, keys or build output.

## Commands

```bash
./gradlew ciCheck                     # source rules, API check, unit tests, sample builds
./gradlew apiDump                     # after an intended public API change
./gradlew publishToMavenLocal         # try the artifacts in another project
./gradlew :agent-runtime-android:connectedDebugAndroidTest   # with a device
```

## Pitfalls

- A Robolectric test that runs the whole app server through the protocol (`TimerAgentTest`, `HabitsAgentTest`,
  `HostApprovalsTest`) runs on the main thread and must turn the main looper while it waits, with `idleFor`: the paused
  looper's clock moves only when told. `AppServer` and `RuntimeSession` are process singletons, so such a test is the
  only one starting the server in its sandbox; several methods in one class share the server the first started (each
  starts a conversation of its own and uses data no other method touches).
- `agent-app-server`'s unit tests run plugin packages' scripts in real QuickJS (Zipline's JVM build replaces the Android
  one on the unit-test classpath); QuickJS reads numbers with the C library's `strtod`, so the test task pins
  `LC_NUMERIC=C`.
- A regex runs on Android's engine on a device and on the JVM's in unit tests: Android refuses a bare closing brace
  (`}`) the JVM accepts, as a crash when the pattern is built. Escape it.
