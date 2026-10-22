# Third-party notices

This project's own source code is licensed under the repository [MIT license](LICENSE). Dependencies resolved by
Gradle, and the third-party files committed in this repository, remain under their respective upstream licenses and
terms; the MIT license does not replace them.

This file is informational and does not modify any upstream license. Use Gradle dependency reports or your
application's software-composition analysis tooling to produce the final, release-specific inventory, including
transitive dependencies and the versions your build actually packages.

## Committed third-party files

### On-device speech (`agent-voice`)

- **sherpa-onnx Kotlin API** (`agent-voice/src/main/kotlin/com/k2fsa/sherpa/onnx/`) — Apache License 2.0, Copyright
  Xiaomi Corporation and the sherpa-onnx authors. Copied from tag v1.13.8; see the `NOTICE` file in that directory.
  Upstream: <https://github.com/k2-fsa/sherpa-onnx>
- **`libsherpa-onnx-jni.so`** (`agent-voice/src/main/jniLibs/arm64-v8a/`) — Apache License 2.0, from the sherpa-onnx
  v1.13.8 Android release (`sherpa-onnx-v1.13.8-android.tar.bz2`, SHA-256
  `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44`).
- **`libonnxruntime.so`** (same directory) — MIT License, Copyright Microsoft Corporation, as bundled in the sherpa-onnx
  v1.13.8 Android release. Upstream: <https://github.com/microsoft/onnxruntime>

sherpa-onnx publishes no Android artifact on Maven Central, so these files are committed. The speech models are not:
an app that uses on-device recognition or the wake word ships them in its own assets
(`voice-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/` and
`voice-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile/`), from the k2-fsa GitHub releases
(<https://github.com/k2-fsa/sherpa-onnx/releases>). The keyword-spotting model was trained on the WenetSpeech corpus,
which OpenSLR lists under CC BY 4.0 (<https://www.openslr.org/121>); check the upstream model pages and the dataset
terms before using these models in a commercial product.

### A2A Java SDK sources (`agent-a2a-client-official`)

Seven small classes from A2A Java SDK `1.1.0.Final` (Apache License 2.0) are included as source; see that module's
`THIRD_PARTY_NOTICES.md`. Upstream: <https://github.com/a2aproject/a2a-java>

## Notable Gradle dependencies

- **JavaScript execution** (`agent-exec-js`): `app.cash.zipline:zipline` — Apache License 2.0
  (<https://github.com/cashapp/zipline>). The Zipline Android artifact includes native QuickJS libraries, distributed
  upstream under the MIT license (<https://bellard.org/quickjs/>).
- **A2A** (`agent-a2a-client-official`): A2A Java SDK client artifacts and Protocol Buffers — Apache License 2.0
  (<https://github.com/a2aproject/a2a-java>) and BSD 3-Clause (<https://github.com/protocolbuffers/protobuf>).
- **SAF mounts** (`agent-vfs-saf`): AndroidX DocumentFile — Apache License 2.0 (<https://github.com/androidx/androidx>).
- **Kotlin coroutines and serialization** — Apache License 2.0 (<https://github.com/Kotlin/kotlinx.coroutines>,
  <https://github.com/Kotlin/kotlinx.serialization>).
- **OkHttp** — Apache License 2.0 (<https://github.com/square/okhttp>).

Applications that include the optional modules redistribute these libraries, including their native artifacts,
through their APK/AAB and should preserve all notices the exact resolved dependency graph requires.
