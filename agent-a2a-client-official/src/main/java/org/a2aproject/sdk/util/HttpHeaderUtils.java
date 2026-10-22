package org.a2aproject.sdk.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Utilities for case-insensitive immutable HTTP header snapshots. */
public final class HttpHeaderUtils {
    private HttpHeaderUtils() {
    }

    public static Map<String, List<String>> copyOfCaseInsensitive(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                List<String> values = new ArrayList<>();
                for (String value : entry.getValue()) {
                    if (value != null) {
                        values.add(value);
                    }
                }
                copy.put(entry.getKey(), Collections.unmodifiableList(values));
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
