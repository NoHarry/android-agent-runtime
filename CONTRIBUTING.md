# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Build and check

You need JDK 17 and the Android SDK (point `sdk.dir` at it in `local.properties`, which is never committed).

```bash
./gradlew ciCheck
```

runs what CI runs:

- **Source rules** (`checkSourceRules`): a file has at most 1000 lines, a function at most 50, and nothing prints to
  stdout. Split a long function rather than raising a limit.
- **API compatibility** (`apiCheck`): the public API of the JVM modules (`agent-kernel-api`, `agent-kernel-core`,
  `agent-vfs`, `agent-runtime`, `agent-minimal`) is recorded in each module's `api/*.api`. When you change it on
  purpose, run `./gradlew apiDump`, commit the new dump, and note any breaking change in `CHANGELOG.md`. The Android
  modules are not covered yet: the validator does not see Kotlin compiled by the Android Gradle plugin.
- **Unit tests** of every module, and the samples' builds.

Device tests (`./gradlew :agent-runtime-android:connectedDebugAndroidTest`, `:agent-exec-js:connectedDebugAndroidTest`)
need a connected device.

## Rules of the codebase

- `agent-kernel-api`, `agent-kernel-core`, `agent-vfs` and `agent-runtime` are plain JVM modules. Android APIs go in
  `agent-runtime-android` or an optional module.
- Optional modules depend at most on `agent-kernel-api` (or `agent-vfs` / `agent-a2a-client` for their own adapters);
  the runtime depends on none of them.
- Every first-party `ToolProvider` and `ModelClient` declares its `providerConcurrency` explicitly, even where the
  default would do; a test checks it.
- Messages a host may show its users have English defaults and a way to replace them (see `McpClientMessages`).
  `agent-voice` is the exception: it is built for Mandarin.
- Public API is 0.x. New API may carry `@ExperimentalAgentRuntimeApi`; keep the quick-start path free of it.

## Tools

`tools/` holds Python helpers for working on the runtime: event-log reports (`session_latency.py`,
`session_cache.py`), the probe analyzer (`bench_analyze`) and A2A interop runners (`tools/a2a`). They are not needed to
build. Their tests: `cd tools && python -m unittest discover -s tests`.
