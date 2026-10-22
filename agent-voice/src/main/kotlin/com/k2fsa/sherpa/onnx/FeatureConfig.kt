// Copied unchanged from k2-fsa/sherpa-onnx v1.13.8 sherpa-onnx/kotlin-api (Apache-2.0, see NOTICE in this
// directory). The JNI library binds to these package, class and field names: do not rename or edit.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
