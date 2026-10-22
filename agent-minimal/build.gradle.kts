plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

description = "A minimal agent profile built straight on the kernel."

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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
