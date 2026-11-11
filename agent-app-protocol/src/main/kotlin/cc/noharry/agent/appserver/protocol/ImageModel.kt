package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * The image generation model, configured apart from the chat providers: any OpenAI-compatible
 * `POST {baseUrl}/images/generations` endpoint. The key lives in a no-backup file and is never
 * echoed back to the UI; [hasApiKey] only says whether one is stored.
 */
@Serializable
data class ImageModelConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    /** `WIDTHxHEIGHT` for providers that honour it; blank sends no size and takes the provider default. */
    val size: String = "",
    val timeoutMillis: Long = DEFAULT_IMAGE_TIMEOUT_MS,
    val hasApiKey: Boolean = false,
) {
    /** Whether the assistant may call the image tool on the next run. */
    val ready: Boolean get() = enabled && hasApiKey && validationError() == null

    /** What is wrong, in English, for the model, the checks and logs; Settings shows [validationProblem]. */
    fun validationError(): String? = validationProblem()?.message

    fun validationProblem(): ImageModelProblem? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val loopbackHttp = uri != null && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
        return when {
            baseUrl.isBlank() && !enabled -> null
            uri == null || uri.host.isNullOrBlank() || (uri.scheme != "https" && !loopbackHttp) ->
                ImageModelProblem.BASE_URL_SCHEME
            uri.userInfo != null || uri.query != null || uri.fragment != null ->
                ImageModelProblem.BASE_URL_PARTS
            model.any(Char::isWhitespace) -> ImageModelProblem.MODEL_WHITESPACE
            // Base URL and key are saved first; the model is then picked from the provider's list.
            enabled && model.isBlank() -> ImageModelProblem.NO_MODEL
            size.isNotBlank() && !IMAGE_SIZE.matches(size.trim()) -> ImageModelProblem.SIZE
            timeoutMillis !in MIN_IMAGE_TIMEOUT_MS..MAX_IMAGE_TIMEOUT_MS -> ImageModelProblem.TIMEOUT
            enabled && !hasApiKey -> ImageModelProblem.NO_KEY
            else -> null
        }
    }
}

/** Why image model settings were not saved: [message] for the model and logs; the server shows it in words. */
@Serializable
enum class ImageModelProblem(val message: String) {
    BASE_URL_SCHEME("Base URL must be https://, or http:// on 127.0.0.1 (adb reverse)."),
    BASE_URL_PARTS("Base URL cannot contain credentials, query or fragment."),
    MODEL_WHITESPACE("Model ID cannot contain whitespace."),
    NO_MODEL("Pick a model before enabling image generation."),
    SIZE("Size must look like 1024x1024, or stay blank."),
    TIMEOUT("Timeout must be 10–300 s."),
    NO_KEY("Add an API key before enabling image generation."),
    KEY_WHITESPACE("API key cannot contain whitespace."),
    KEY_NOT_REMOVED("Could not remove the stored key."),
    NOT_SAVED("Could not save image model settings."),
    KEY_NOT_SAVED("Could not save the API key."),
}

/** A generated image saved in app-private storage; [reference] is its path under `filesDir`. */
@Serializable
data class GeneratedImageResult(
    val reference: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val elapsedMillis: Long,
    val revisedPrompt: String?,
)

/** New settings, with a key to store ([replacementKey], blank keeps the stored one) or [clearKey]. */
@Serializable
data class ImageModelSaveParams(val config: ImageModelConfig, val replacementKey: String = "", val clearKey: Boolean = false)

/** The settings as now stored, and [problem] when nothing was saved. */
@Serializable
data class ImageModelSaveResult(val config: ImageModelConfig, val problem: ImageModelProblem? = null)

/** One test generation with the saved settings: the picture, or why there is none. */
@Serializable
data class ImageTryResult(val image: GeneratedImageResult? = null, val failure: String? = null, val elapsedMillis: Long = 0)

/** The image model settings. Keys go in only; the settings say whether one is stored ([ImageModelConfig.hasApiKey]). */
object ImageModelRequests {
    val READ = RequestMethod("imageModel/read", Empty.serializer(), ImageModelConfig.serializer())
    val SAVE = RequestMethod("imageModel/save", ImageModelSaveParams.serializer(), ImageModelSaveResult.serializer())

    /** The model IDs the saved provider lists; refused with why when they cannot be fetched. */
    val MODELS = RequestMethod("imageModel/models", Empty.serializer(), ModelListResult.serializer())
    val TRY = RequestMethod("imageModel/try", TextParams.serializer(), ImageTryResult.serializer())
}

/** Generations take 15–30 s on the providers tried; two minutes leaves room for a slow one. */
const val DEFAULT_IMAGE_TIMEOUT_MS = 120_000L
private const val MIN_IMAGE_TIMEOUT_MS = 10_000L
private const val MAX_IMAGE_TIMEOUT_MS = 300_000L
private val IMAGE_SIZE = Regex("""\d{2,5}x\d{2,5}""")
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

/** Whether an ID reads like an image model, so gateways listing every model show these first. */
fun looksLikeImageModel(id: String): Boolean {
    val lower = id.lowercase()
    if (IMAGE_MODEL_HINTS.any { it in lower }) return true
    return lower.split('-', '_', '/', '.', ':', ' ').any { it in IMAGE_MODEL_TOKENS }
}

private val IMAGE_MODEL_HINTS = listOf(
    "image", "dall-e", "dalle", "flux", "seedream", "seededit", "imagen", "midjourney", "stable-diffusion", "kolors",
    "cogview", "wanx", "recraft", "ideogram", "jimeng", "nano-banana", "hidream", "playground",
)
private val IMAGE_MODEL_TOKENS = setOf("sd", "sdxl", "sd3", "mj")
