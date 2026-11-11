package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState

/**
 * Image input per saved model: on (text and pictures) by default; off means text only.
 * Off folds every request image into text and keeps Android Use to elements without screenshots.
 */
internal fun loadModelImageInput(context: Context, profile: ProviderEditState): Boolean =
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE)
        .getBoolean(modelIdentityKey(profile), DEFAULT_MODEL_IMAGE_INPUT)

internal fun saveModelImageInput(context: Context, profile: ProviderEditState, enabled: Boolean) {
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (enabled == DEFAULT_MODEL_IMAGE_INPUT) remove(modelIdentityKey(profile)) else putBoolean(modelIdentityKey(profile), enabled)
    }.apply()
}

internal const val MODEL_IMAGE_INPUT_PREFS = "app_server_model_image_input"
private const val DEFAULT_MODEL_IMAGE_INPUT = true
