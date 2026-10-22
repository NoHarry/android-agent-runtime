// Copied unchanged from k2-fsa/sherpa-onnx v1.13.8 sherpa-onnx/kotlin-api (Apache-2.0, see NOTICE in this
// directory). The JNI library binds to these package, class and field names: do not rename or edit.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
