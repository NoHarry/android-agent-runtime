plugins {
    id("com.android.application")
}

/*
 * A kitchen timer assistant on the app server with no screen at all: its own persona, tools, system rule and a scripted
 * model the simulated backend plays (ScriptedModel); the only way in is an exported receiver that runs one turn per
 * intent and logs to logcat. Its unit test runs the same turns offline, through the protocol.
 */
android {
    namespace = "cc.noharry.agent.appserver.sample.timer"
    compileSdk = 34
    defaultConfig {
        applicationId = "cc.noharry.agent.appserver.sample.timer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        // The app server's workspace and event log use java.nio.file, which Android has from API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The server reads its own assets (the workspace it gives the model) in the test too.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
    // The app server is experimental: its entry points ask for this opt-in.
    compilerOptions.optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":agent-app-server"))
    implementation(project(":agent-app-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
