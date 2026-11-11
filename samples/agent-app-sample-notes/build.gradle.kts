plugins {
    id("com.android.application")
}

/*
 * A second app on the app server, written against its public API alone: a notes assistant with a tool provider of its
 * own, rules, a gate that asks before clearing every note, a model service the build may carry, one screen (which also
 * connects another model and answers approvals), and a foreground service that takes a request with no screen at all.
 *
 * The model service: -PnotesModelBaseUrl=https://… -PnotesModel=… -PnotesModelKey=… (or the same keys in
 * ~/.gradle/gradle.properties). Empty by default: the person then connects one on the screen. A key built in is in the
 * APK for anyone who has it to read; an app people install takes its key from its own server or from the person.
 */
// guide:start build-model-property
/** Gradle property [name] as a Java string literal for buildConfigField; empty when it is not set. */
fun gradleString(name: String): String =
    "\"" + (findProperty(name) as String?).orEmpty().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// guide:end build-model-property

android {
    namespace = "cc.noharry.agent.appserver.sample.notes"
    compileSdk = 34
    defaultConfig {
        applicationId = "cc.noharry.agent.appserver.sample.notes"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // guide:start build-model
        // The model service the build carries (NotesApplication's builtModel); with buildFeatures.buildConfig on.
        buildConfigField("String", "MODEL_BASE_URL", gradleString("notesModelBaseUrl"))
        buildConfigField("String", "MODEL_NAME", gradleString("notesModel"))
        buildConfigField("String", "MODEL_KEY", gradleString("notesModelKey"))
        // guide:end build-model
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        // The app server's workspace and event log use java.nio.file, which Android has from API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}
