plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

description = "One version for every Android Agent Runtime module."

dependencies {
    constraints {
        listOf(
            ":agent-kernel-api", ":agent-kernel-core", ":agent-minimal", ":agent-vfs", ":agent-runtime",
            ":agent-runtime-android", ":agent-vfs-saf", ":agent-exec-js", ":agent-device-capabilities",
            ":agent-android-use", ":agent-voice", ":agent-a2a-client", ":agent-a2a-client-direct",
            ":agent-a2a-client-official", ":agent-a2a-server",
        ).forEach { api(project(it)) }
    }
}
