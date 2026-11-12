# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Build and check

You need JDK 17, the Android SDK (point `sdk.dir` at it in `local.properties`, which is never committed) and Python 3
as `python3` on the `PATH`: `apiCheck` and `apiDump` run `tools/app-server-api/api_dump.py`, and fail with a message
saying so when `python3` is missing. Some of the app server's tests also use `python3` and `openssl` where they are
installed (below).

```bash
./gradlew ciCheck
```

runs what CI runs:

- **Source rules** (`checkSourceRules`): a file has at most 1000 lines, a function at most 50, and nothing prints to
  stdout. Split a long function rather than raising a limit.
- **API compatibility** (`apiCheck`): the public API of every published module but the BOM is recorded in its
  `api/*.api` (the Android modules' from their release classes, Kotlin and Java).
  `agent-app-server`'s is also listed by who may use each declaration (a host, a plugin module, another module's tests)
  in `api/agent-app-server-levels.txt`, which `tools/app-server-api/api_dump.py` writes and checks (Python 3). When you
  change it on purpose, run `./gradlew apiDump`, commit the new dump, and note any breaking change in `CHANGELOG.md`.
- **The app server's layering** (`checkAppServerLayering`): its core names no plugin's package (the first-party
  plugins but three are source modules of the Bothy app) and no model service or its models
  (`checkCoreServiceNames`), and carries none of the modules only a plugin brings (`agent-a2a`, `agent-android-use`,
  `agent-device-capabilities`, `agent-app-models`). The other way round, `agent-android-use` and
  `agent-device-capabilities` carry a plugin each without carrying the server (`checkNoAppServerDependency`): only their
  `appserver` package and their tests name it, and their tests but the plugin's run again without it
  (`testWithoutAppServer`).
- **Android API level**: the JVM modules run on Android from API 23, so their main classes are checked against
  Android 23's API (`animalsnifferMain`; the kernel and the runtime may also use what `desugar_jdk_libs_nio` 2.1.5 adds
  but Android 26 has itself; so may `agent-a2a`'s official client, `animalsnifferOfficialClient`, as the SDK it runs on
  needs Android 7.0 or desugaring), and the Android libraries run lint, whose errors (such as `NewApi`) fail.
- **Unit tests** of every module, the samples' included, and the samples' builds. `agent-a2a`'s but the official
  client's run again without the official SDK (`testWithoutOfficialSdk`). Some of the app server's tests run
  `tools/agent-plugin/agentplugin.py` where `python3` and `openssl` are installed.

CI also publishes every artifact to the local Maven repository, then builds, tests and runs `samples/extensions`
against the published `agent-runtime` and the BOM, and builds the apps on the app server against the published
`agent-app-server`, `agent-app-protocol`, `agent-app-models` and `agent-device-capabilities`, testing the two tested offline (`./gradlew publishToMavenLocal`, then the
same tasks with `-PsamplesFromMavenLocal`), so published dependency metadata that leaves out what a host needs fails
there.

Device tests (`./gradlew :agent-runtime-android:connectedDebugAndroidTest`, `:agent-exec-js:connectedDebugAndroidTest`)
need a connected device. `./gradlew :agent-app-models:realServiceTest` runs the model services themselves, each whose
key is in the environment (`AGENT_E2E_<SERVICE>_KEY`; see the host guide's "Model services"); it is never part of
`ciCheck`.

## Rules of the codebase

- `agent-api`, `agent-runtime` and `agent-a2a` are plain JVM modules. Android APIs go in
  `agent-runtime-android` or an Android optional module.
- Optional modules depend at most on `agent-api`; the two with an app server plugin also compile against the server
  (`compileOnly`), and `agent-a2a` against the official A2A Java SDK, never carrying them. The runtime depends on none
  of them.
- Every first-party `ToolProvider` and `ModelClient` declares its `providerConcurrency` explicitly, even where the
  default would do; a test checks it.
- Messages a host may show its users have English defaults and a way to replace them (see `McpClientMessages`).
- The conversation API and main SPI are stable from 0.5.0. Advanced API may carry `@ExperimentalAgentRuntimeApi`; keep the quick-start path free of it.

## Tools

`tools/` holds Python helpers for working on the runtime: event-log reports (`session_latency.py`,
`session_cache.py`), the probe analyzer (`bench_analyze`), A2A interop runners (`tools/a2a`), the app server's plugin
package maker (`tools/agent-plugin`) and its API listing by level (`tools/app-server-api`). Only the listing is needed
to build: `apiCheck` and `apiDump` run it with `python3`. Their tests: `cd tools && python -m unittest discover -s tests`.

## Releasing

Push a tag `vX.Y.Z`: the release workflow checks the build and uploads `X.Y.Z` to the Maven Central Portal, where it
waits for a manual Publish. Then date `CHANGELOG.md`'s entry, set `RELEASED_VERSION` in `gradle.properties` to the release (the version the
READMEs and the host guide name) and move `VERSION_NAME` on to the next snapshot. The README's Maven Central badge follows the release by itself. Tags `v*` cannot be moved or deleted (a
ruleset): to tag a failed release again, disable that ruleset first.
