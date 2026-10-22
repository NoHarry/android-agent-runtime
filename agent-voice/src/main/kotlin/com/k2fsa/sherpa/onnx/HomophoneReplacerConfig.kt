// Copied unchanged from k2-fsa/sherpa-onnx v1.13.8 sherpa-onnx/kotlin-api (Apache-2.0, see NOTICE in this
// directory). The JNI library binds to these package, class and field names: do not rename or edit.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
