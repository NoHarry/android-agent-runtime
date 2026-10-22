package org.a2aproject.sdk.util;

/** Parameter validation utilities. */
public final class Assert {
    private Assert() {
    }

    public static <T> T checkNotNullParam(String name, T value) {
        if (name == null) {
            throw new IllegalArgumentException("Parameter 'name' may not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Parameter '" + name + "' may not be null");
        }
        return value;
    }

    public static void isValidJsonRpcId(Object value) {
        if (!(value == null || value instanceof String || value instanceof Number)) {
            throw new IllegalArgumentException("JSON-RPC id must be null, a String, or a Number");
        }
    }
}
