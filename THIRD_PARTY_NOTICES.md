# Third-party notices

This project's own source code is licensed under the repository [MIT license](LICENSE). Dependencies resolved by
Gradle, and the third-party files committed in this repository, remain under their respective upstream licenses and
terms; the MIT license does not replace them.

This file is informational and does not modify any upstream license. Use Gradle dependency reports or your
application's software-composition analysis tooling to produce the final, release-specific inventory, including
transitive dependencies and the versions your build actually packages.

## Committed third-party files

### A2A Java SDK sources (`agent-a2a-client-official`)

Seven small classes from A2A Java SDK `1.1.0.Final` (Apache License 2.0) are included as source; see that module's
`THIRD_PARTY_NOTICES.md`. Upstream: <https://github.com/a2aproject/a2a-java>

## Notable Gradle dependencies

- **JavaScript execution** (`agent-exec-js`): `app.cash.zipline:zipline` — Apache License 2.0
  (<https://github.com/cashapp/zipline>). The Zipline Android artifact includes native QuickJS libraries, distributed
  upstream under the MIT license (<https://bellard.org/quickjs/>).
- **A2A** (`agent-a2a-client-official`): A2A Java SDK client artifacts and Protocol Buffers — Apache License 2.0
  (<https://github.com/a2aproject/a2a-java>) and BSD 3-Clause (<https://github.com/protocolbuffers/protobuf>).
- **SAF mounts** (`agent-runtime-android`): AndroidX DocumentFile — Apache License 2.0 (<https://github.com/androidx/androidx>).
- **Kotlin coroutines and serialization** — Apache License 2.0 (<https://github.com/Kotlin/kotlinx.coroutines>,
  <https://github.com/Kotlin/kotlinx.serialization>).
- **OkHttp** — Apache License 2.0 (<https://github.com/square/okhttp>).

Applications that include the optional modules redistribute these libraries, including their native artifacts,
through their APK/AAB and should preserve all notices the exact resolved dependency graph requires.
