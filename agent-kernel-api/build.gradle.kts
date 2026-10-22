plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

description = "Android-neutral contracts of the agent runtime: model, tool, plugin, capability, workspace, event log."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    testImplementation("junit:junit:4.13.2")
}
