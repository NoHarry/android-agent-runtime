plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

description = "The app server: an agent runtime behind a JSON-RPC protocol of threads, turns and items, with " +
    "its plugin kernel. Experimental."

/*
 * The app server: the agent runtime, sessions, models, MCP, usage and the plugin kernel behind the protocol
 * (:agent-app-protocol), with no screens and nothing of any one app. A host app gives it its features, its assistant's
 * persona and what it plugs into the runtime (AppServerConfig); front ends reach it only through the protocol.
 */
android {
    namespace = "cc.noharry.agent.appserver"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-proguard-rules.pro")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The app's targetSdk; Robolectric runs up to API 34.
        targetSdk = 34
        unitTests.isIncludeAndroidResources = true
    }
}

// The app server is experimental (ExperimentalAppServerApi); its own code may use what is public only for other
// modules' tests (InternalAppServerApi).
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
        optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
        optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")
    }
}

// The version the server is built as, for what plugin packages name in `minServer` (PackageFormat.SERVER_VERSION): a
// release built with -PVERSION_NAME=<tag> knows its own.
val buildVersion = tasks.register<VersionSourceTask>("generateBuildVersion") {
    packageName.set("cc.noharry.agent.appserver.packages")
    constant.set("BUILT_VERSION")
    version.set(providers.gradleProperty("VERSION_NAME"))
}
androidComponents {
    onVariants { variant -> variant.sources.kotlin?.addGeneratedSourceDirectory(buildVersion, VersionSourceTask::outputDirectory) }
}

// AGP's local-test resource APK must also refresh when the workspace assets or the tests' own (a host's skills and
// subagent types as fixtures) change.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}UnitTestForUnitTest" }.configureEach {
        dependsOn("merge${variant}Assets")
        inputs.dir("src/main/assets")
        inputs.dir("src/test/assets")
    }
}

dependencies {
    // The runtime's workspace and event log use java.nio.file, which Android has from API 26 (minSdk here is 24).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // `api` for what the host-facing API names (the runtime's ModelClient, ToolProvider and AgentRuntime, OkHttp's
    // client): a host implementing HostModels or RunAddOns compiles against them. A2A's modules come with its plugin.
    api(project(":agent-runtime-android"))
    api(project(":agent-app-protocol"))
    // The JavaScript sandbox scripts run in (packages' tools written as scripts, the scripts built-in) is the host's to
    // add: compiled against here and not carried, since its QuickJS is about 4 MB of native code (JsSandbox).
    compileOnly(project(":agent-exec-js"))
    testImplementation(project(":agent-exec-js"))
    implementation(project(":agent-acp"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}

// The unit tests run plugin packages' scripts in the sandbox's real engine (QuickJS through Zipline, as agent-exec-js
// uses it): Zipline's JVM build carries QuickJS for macOS and Linux, its Android build only for devices. Same version.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("app.cash.zipline:zipline")).using(module("app.cash.zipline:zipline-jvm:1.16.0"))
    }
}

// GuideSnippetsTest reads the host guide, the package tool's README, the samples' code and the root build files the guide
// quotes, and the version, and PackageToolTest the timer sample's package: a change to any of them runs the tests again.
tasks.withType<Test>().configureEach {
    inputs.file("README.md").withPropertyName("hostGuide")
    inputs.file("../tools/agent-plugin/README.md").withPropertyName("packageToolGuide")
    inputs.file("../build.gradle.kts").withPropertyName("guideRootBuild")
    inputs.file("../settings.gradle.kts").withPropertyName("guideRootSettings")
    inputs.file("../gradle.properties").withPropertyName("guideVersion")
    inputs.files(
        fileTree("../samples") { include("agent-app-sample*/build.gradle.kts", "agent-app-sample*/src/**", "agent-app-sample*/package/**") },
    ).withPropertyName("guideSamples")
}

// QuickJS reads numbers (literals, parseFloat, JSON.parse) with the C library's strtod, which follows the process's
// numeric locale: a test JVM started under a locale that writes 12,5 would read 12.5 as 12, so the engine runs no script
// there (agent-exec-js checks). The tests run under C's; LC_ALL, which would win over LC_NUMERIC, gives way, its character
// set kept. Android's C library reads numbers the same in every locale.
val commaLocaleTest = "testScriptsUnderACommaLocale"
val allLocale = providers.environmentVariable("LC_ALL")
tasks.withType<Test>().configureEach {
    if (name != commaLocaleTest) environment("LC_NUMERIC", "C")
    allLocale.orNull?.takeIf { it.isNotEmpty() }?.let {
        environment("LC_CTYPE", it)
        environment("LC_ALL", "")
    }
}

// The same script under a locale that writes 0,5 (where the machine has one, as macOS does): its numbers come out right
// or it does not run, never misread.
val scriptsUnderACommaLocale = tasks.register<Test>(commaLocaleTest) {
    group = "verification"
    description = "A package's script under a numeric locale that writes 0,5 computes right or does not run."
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = files(unitTests.map { it.testClassesDirs })
    classpath = files(unitTests.map { it.classpath })
    filter.includeTestsMatching("cc.noharry.agent.appserver.packages.ScriptRunnerTest.numbersWithFractions*")
    environment("LC_NUMERIC", "de_DE.UTF-8")
}
tasks.named("check") { dependsOn(scriptsUnderACommaLocale) }
rootProject.tasks.named("ciCheck") { dependsOn(scriptsUnderACommaLocale) }

// What may use each public declaration (a host; a plugin module, @AppServerPluginApi; another module's tests,
// @InternalAppServerApi), listed in api/agent-app-server-levels.txt by tools/app-server-api/api_dump.py: apiCheck fails
// when a declaration or its level changes, apiDump writes the listing again with the binary one.
val apiLevelsScript = rootProject.file("tools/app-server-api/api_dump.py")
val apiLevelsCheck = tasks.register<Exec>("apiLevelsCheck") {
    group = "verification"
    description = "The public API by level matches api/agent-app-server-levels.txt."
    inputs.files(apiLevelsScript, "api/agent-app-server-levels.txt").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/java").withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst(RequirePython3)
    commandLine("python3", apiLevelsScript.path)
}
val apiLevelsDump = tasks.register<Exec>("apiLevelsDump") {
    group = "other"
    doFirst(RequirePython3)
    commandLine("python3", apiLevelsScript.path, "--write")
}
tasks.named("apiCheck") { dependsOn(apiLevelsCheck) }
tasks.named("apiDump") { dependsOn(apiLevelsDump) }
