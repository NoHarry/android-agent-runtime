plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cc.noharry.agent.samples.quickstart.QuickstartKt")
}

dependencies {
    implementation(project(":agent-runtime"))
}
