package cc.noharry.agent.runtime

import okhttp3.HttpUrl

/**
 * The picture a page names for itself (og:image, then twitter:image), as an absolute https URL, or null. Text
 * extraction drops every image, so without it a model that read the page has no real picture to show (owner
 * 2026-10-17: 这种查询如果能带图片的最好是图文的); a host may still refuse to load it.
 */
internal fun webPageLeadImage(html: String, page: HttpUrl): String? {
    val metas = META_TAG.findAll(html.take(MAX_LEAD_IMAGE_SCAN_CHARS)).map { it.value }.toList()
    for (key in LEAD_IMAGE_KEYS) {
        val tag = metas.firstOrNull { tag ->
            tag.attribute("property").equals(key, ignoreCase = true) || tag.attribute("name").equals(key, ignoreCase = true)
        } ?: continue
        val content = tag.attribute("content")?.replace("&amp;", "&")?.trim().orEmpty()
        val resolved = page.resolve(content) ?: continue
        if (resolved.isHttps && content.isNotEmpty()) return resolved.toString()
    }
    return null
}

private fun String.attribute(name: String): String? =
    Regex("""(?i)\b$name\s*=\s*["']([^"']*)["']""").find(this)?.groupValues?.get(1)

private val META_TAG = Regex("""(?i)<meta\b[^>]*>""")
private val LEAD_IMAGE_KEYS = listOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
/** Pages declare it in their head; a long body need not be searched. */
private const val MAX_LEAD_IMAGE_SCAN_CHARS = 64 * 1024
