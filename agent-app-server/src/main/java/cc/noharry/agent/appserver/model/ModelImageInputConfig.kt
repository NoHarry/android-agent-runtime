package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState

/**
 * Image input per saved model: as the person set it, else as its facts say ([modelFacts]), else on (text and pictures);
 * off means text only. Off folds every request image into text and keeps Android Use to elements without screenshots.
 */
internal fun loadModelImageInput(context: Context, profile: ProviderEditState): Boolean =
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE)
        .getBoolean(modelIdentityKey(profile), defaultImageInput(profile))

internal fun saveModelImageInput(context: Context, profile: ProviderEditState, enabled: Boolean) {
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (enabled == defaultImageInput(profile)) remove(modelIdentityKey(profile)) else putBoolean(modelIdentityKey(profile), enabled)
    }.apply()
}

private fun defaultImageInput(profile: ProviderEditState): Boolean =
    modelFacts(profile.baseUrl, profile.model).imageInput ?: DEFAULT_MODEL_IMAGE_INPUT

internal const val MODEL_IMAGE_INPUT_PREFS = "app_server_model_image_input"
private const val DEFAULT_MODEL_IMAGE_INPUT = true
