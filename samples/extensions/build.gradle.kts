plugins {
    kotlin("jvm")
    application
}

// Not published: the runtime's extension points, run with no network and no key.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cc.noharry.agent.samples.extensions.ExtensionsKt")
}

dependencies {
    if (providers.gradleProperty("samplesFromMavenLocal").orNull.let { it != null && it != "false" }) {
        // The published artifacts, through the BOM, from ./gradlew publishToMavenLocal: their POMs and Gradle metadata
        // must give an app everything it compiles and runs against.
        implementation(platform("cc.noharry:agent-bom:$version"))
        implementation("cc.noharry:agent-runtime")
    } else {
        implementation(project(":agent-runtime"))
    }

    testImplementation("junit:junit:4.13.2")
}
