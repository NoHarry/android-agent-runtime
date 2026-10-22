package org.a2aproject.sdk.util;

/** Utilities for reversing Gson HTML-safe JSON escaping. */
public final class HtmlEscapeUtils {
    private HtmlEscapeUtils() {
    }

    public static String removeHtmlEscaping(String json) {
        if (json == null || json.isEmpty() || !json.contains("\\u00")) {
            return json;
        }
        return json.replace("\\u003c", "<").replace("\\u003C", "<")
                .replace("\\u003e", ">").replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=").replace("\\u003D", "=")
                .replace("\\u0027", "'");
    }
}
