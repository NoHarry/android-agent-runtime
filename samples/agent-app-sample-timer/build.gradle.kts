plugins {
    id("com.android.application")
}

/*
 * A kitchen timer assistant on the app server with no screen at all: its own persona, tools, system rule and a scripted
 * model the simulated backend plays (ScriptedModel); the only way in is an exported receiver that runs one turn per
 * intent and logs to logcat. Its unit test runs the same turns offline, through the protocol, and installs a plugin
 * package (package/, built and signed into src/test/resources) whose tool is a script.
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
    // guide:start scripts-dependency
    // Plugin packages' tools written as scripts run in this sandbox (QuickJS through Zipline); without it the server
    // refuses such a package.
    implementation(project(":agent-exec-js"))
    // guide:end scripts-dependency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

// guide:start script-tests
// A unit test runs the scripts in QuickJS too, under the JVM: Zipline's JVM build carries QuickJS for macOS and
// Linux x86-64, its Android build only for devices. The same version agent-exec-js uses.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("app.cash.zipline:zipline")).using(module("app.cash.zipline:zipline-jvm:1.16.0"))
    }
}
// QuickJS reads numbers by the C library's numeric locale, which the JVM takes from the environment: under one that
// writes 0,5 it runs no script. LC_ALL would win over LC_NUMERIC, so it gives way (its character set kept).
val allLocale = providers.environmentVariable("LC_ALL")
tasks.withType<Test>().configureEach {
    environment("LC_NUMERIC", "C")
    allLocale.orNull?.takeIf { it.isNotEmpty() }?.let {
        environment("LC_CTYPE", it)
        environment("LC_ALL", "")
    }
}
// guide:end script-tests
