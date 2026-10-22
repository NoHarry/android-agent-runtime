package org.a2aproject.sdk.common;

/** Standard error message constants for A2A authentication and authorization failures. */
public final class A2AErrorMessages {
    public static final String AUTHENTICATION_FAILED =
            "Authentication failed: Client credentials are missing or invalid";
    public static final String AUTHORIZATION_FAILED =
            "Authorization failed: Client does not have permission for the operation";

    private A2AErrorMessages() {
    }
}
