plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

description = "One version for every Android Agent Runtime module."

dependencies {
    constraints {
        listOf(
            ":agent-api", ":agent-runtime", ":agent-runtime-android",
            ":agent-exec-js", ":agent-device-capabilities", ":agent-android-use", ":agent-a2a", ":agent-acp",
            ":agent-app-protocol", ":agent-app-server", ":agent-app-models",
        ).forEach { api(project(it)) }
    }
}
