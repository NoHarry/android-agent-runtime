plugins {
    id("com.android.application")
}

/*
 * The smallest app on the app server: no features, the generic persona, one screen that is a protocol client. What a
 * standalone phone agent, or any app taking the server as its core, starts from; it uses no app's plugins.
 */
android {
    namespace = "cc.noharry.agent.appserver.sample"
    compileSdk = 34
    defaultConfig {
        applicationId = "cc.noharry.agent.appserver.sample"
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

/**
 * A host that turns nothing on and lists no plugin merges no permission or component it did not ask for: only its own
 * INTERNET and screen, and the server's boot receiver (off until a running plugin asks to start after a reboot). Every
 * build of the sample checks its merged manifest, so a library that brings more fails it.
 */
abstract class BareManifestCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val permissions = Regex("""<(?:uses-)?permission[^>]*?android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSortedSet()
        val components = Regex("""<(?:activity|activity-alias|service|receiver|provider)\s[^>]*?android:name="([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }.toSortedSet()
        val extra = (permissions - ALLOWED_PERMISSIONS) + (components - ALLOWED_COMPONENTS) +
            listOfNotNull("<queries>".takeIf { "<queries" in text })
        report.get().asFile.writeText((listOf("permissions:") + permissions + "components:" + components).joinToString("\n"))
        check(extra.isEmpty()) {
            "agent-app-sample's merged manifest has what a bare host did not ask for: $extra. A host gets operating the " +
                "screen and the device's tools only from plugins it lists, whose modules bring their manifests."
        }
    }

    private companion object {
        val ALLOWED_PERMISSIONS = setOf("android.permission.INTERNET")
        val ALLOWED_COMPONENTS = setOf(
            "cc.noharry.agent.appserver.sample.MainActivity",
            "cc.noharry.agent.appserver.BootReceiver",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val check = tasks.register<BareManifestCheck>("check${name}BareManifest") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("bare-manifest/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach { dependsOn(check) }
    }
}
