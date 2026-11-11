plugins {
    id("com.android.application")
}

/*
 * A second app on the app server, written against its public API alone: a notes assistant with a tool provider of its
 * own, rules, a gate that asks before clearing every note, a model service the build may carry, one screen (which also
 * connects another model and answers approvals), and a foreground service that takes a request with no screen at all.
 * It depends on agent-app-models, so the server knows the common model services; its code names none of them.
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
    buildTypes {
        // R8 with no rules of the app's own, as the bare sample: the model services' plugin, found from its manifest,
        // is kept by the server's rules (CI checks it, checkReleaseDiscoveredPlugins).
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
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
    // guide:start models-dependency
    // The model services (DeepSeek, OpenAI, Anthropic and more): loaded by default, named nowhere in the app's code.
    implementation(project(":agent-app-models"))
    // guide:end models-dependency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}

/**
 * The plugins the release's merged manifest declares for the server to find ([PluginDiscovery]) are in the release R8
 * made, under their own names, with what they are made by kept: a release that lost one would run without it, saying
 * nothing. Read from the merged manifest, R8's mapping file and the seeds R8 writes beside it (what its rules kept).
 */
abstract class DiscoveredPluginsCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val entries = Regex("""<meta-data\s[^>]*>""").findAll(manifest.get().asFile.readText()).map { it.value }
        val declared = entries.filter { """android:value="cc.noharry.agent.appserver.plugin"""" in it }
            .mapNotNull { Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1) }.toSortedSet()
        check(declared.isNotEmpty()) { "The notes release's merged manifest declares no plugin; agent-app-models declares one." }
        val lines = mapping.get().asFile.readLines()
        val seeds = mapping.get().asFile.resolveSibling("seeds.txt").readLines()
        declared.forEach { name ->
            check("$name -> $name:" in lines) { "R8 removed or renamed $name, which the release's manifest declares as a plugin." }
            check("$name: $name INSTANCE" in seeds || "$name: ${name.substringAfterLast('.')}()" in seeds) {
                "R8 kept $name without the way it is made (its INSTANCE, or a constructor that takes nothing)."
            }
        }
        report.get().asFile.writeText(declared.joinToString("\n"))
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val check = tasks.register<DiscoveredPluginsCheck>("checkReleaseDiscoveredPlugins") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            mapping.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE))
            report.set(layout.buildDirectory.file("discovered-plugins/release.txt"))
        }
        rootProject.tasks.named("ciCheck") { dependsOn(check) }
    }
}
