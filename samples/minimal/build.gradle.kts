plugins {
    kotlin("jvm")
}

// Not published: the smallest agent built straight on the kernel, a starting point for a runtime of your own.
description = "A minimal agent profile built straight on the kernel."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":agent-kernel-api"))
    implementation(project(":agent-kernel-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
