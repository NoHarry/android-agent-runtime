plugins {
    id("com.android.application")
}

android {
    namespace = "cc.noharry.agent.samples.quickstart.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.noharry.agent.samples.quickstart"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        // The runtime's workspace and event log use java.nio.file, which Android has from API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    implementation(project(":agent-runtime-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}
