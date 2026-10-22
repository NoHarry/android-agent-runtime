plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

description = "The runtime and host facade on the JVM: model clients, MCP, skills, subagents, sessions, hooks and policy."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":agent-kernel-api"))
    api(project(":agent-kernel-core"))
    api(project(":agent-vfs"))
    // Public API exposes Flow/suspend signatures; api scope keeps external consumers compiling.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    // Public policy APIs expose JsonObject; api scope keeps external consumers compiling.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
