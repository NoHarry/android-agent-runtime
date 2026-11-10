plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

description = "Agent Client Protocol (ACP): serve the agent's threads to editors and other ACP clients over stdio or " +
    "any stream."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":agent-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":agent-runtime"))
}
