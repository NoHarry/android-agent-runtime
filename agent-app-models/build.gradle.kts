plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

description = "The app server's model services: what is known of DeepSeek, OpenAI, Anthropic and other services " +
    "(connection defaults, models, facts, prices, failures, DeepSeek's extras), loaded by default. Experimental."

/*
 * The model services' plugin of the app server: one plugin (ModelsPlugin), declared in this module's manifest so a host
 * that depends on the module gets it without listing it, with an adapter per service. What is known of each service is
 * data (src/main/resources/.../services.json, each with its sources and the day they were read); DeepSeek's extras
 * (its search and fetch APIs, native updates, balance, the check of a relay, one key for its profiles) are code. The
 * server (agent-app-server) knows nothing of it: it speaks the wire protocols alone.
 */
android {
    namespace = "cc.noharry.agent.appserver.models"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        targetSdk = 34
        unitTests.isIncludeAndroidResources = true
    }
}

// The app server is experimental (ExperimentalAppServerApi); a plugin builds on what the server keeps for plugins
// (AppServerPluginApi).
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
        optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    api(project(":agent-app-server"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}

// GuideServicesTableTest reads the host guide's table of the services: a change to it runs the tests again.
tasks.withType<Test>().configureEach { inputs.file("../agent-app-server/README.md").withPropertyName("hostGuide") }

// Its tests check how the server uses the adapters, through names the server keeps public only for tests.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}

// The services themselves, with the keys a person gives in the environment (AGENT_E2E_<SERVICE>_KEY, and optionally
// AGENT_E2E_<SERVICE>_MODEL): RealServiceTest lists each one's models, runs a plain turn and one with a tool call, and
// checks their usage and cost. A service without a key is skipped. Never part of check or ciCheck, and never cached:
//     ./gradlew :agent-app-models:realServiceTest
val realServiceTest = tasks.register<Test>("realServiceTest") {
    group = "verification"
    description = "Each model service whose key is in the environment, against the service itself."
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = files(unitTests.map { it.testClassesDirs })
    classpath = files(unitTests.map { it.classpath })
    filter.includeTestsMatching("cc.noharry.agent.appserver.models.RealServiceTest")
    systemProperty("agent.e2e", "true")
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
tasks.withType<Test>().matching { it.name != realServiceTest.name }.configureEach {
    filter.excludeTestsMatching("cc.noharry.agent.appserver.models.RealServiceTest")
}
