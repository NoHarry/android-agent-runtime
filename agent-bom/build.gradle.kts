plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

description = "One version for every Android Agent Runtime module."

dependencies {
    constraints {
        listOf(
            ":agent-kernel-api", ":agent-kernel-core", ":agent-runtime", ":agent-runtime-android",
            ":agent-exec-js", ":agent-device-capabilities", ":agent-android-use", ":agent-a2a",
            ":agent-a2a-client-official",
        ).forEach { api(project(it)) }
    }
}
