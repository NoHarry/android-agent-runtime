import com.vanniktech.maven.publish.MavenPublishBaseExtension

// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugin on this classpath sets the compiler
// version for Android and JVM modules alike.
plugins {
    base
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

/** The Kotlin standard library the artifacts ask for: the oldest whose API they use (apiVersion 2.1). */
val KOTLIN_LIBRARIES_VERSION = "2.1.21"

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = sdkVersion
}

apiValidation {
    // Samples and the BOM have no API of their own.
    ignoredProjects += listOf("quickstart-jvm", "quickstart-android", "minimal", "agent-bom")
    // Public only for the runtime's own modules and tests.
    nonPublicMarkers += "cc.noharry.agent.kernel.api.InternalAgentRuntimeApi"
}

// Unit tests create temp dirs (createTempDirectory, TemporaryFolder, Robolectric) and rarely delete them,
// which left 100k+ folders in the system Temp. Give every Test task its own java.io.tmpdir under the
// module's build directory, emptied before each run, so leftovers stay bounded and `clean` removes them.
subprojects {
    // Interface bodies compile to JVM default methods, so a Java class implementing ModelClient, ToolProvider or a hook
    // writes only what it changes; the DefaultImpls classes stay beside them for Kotlin hosts compiled against 0.5.0 and
    // earlier, so this must never become NO_COMPATIBILITY. Language and API level 2.1, with the 2.1 standard library,
    // let hosts on Kotlin 2.0 and later read the artifacts.
    listOf("org.jetbrains.kotlin.jvm", "com.android.library", "com.android.application").forEach { id ->
        pluginManager.withPlugin(id) {
            (extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension).coreLibrariesVersion =
                KOTLIN_LIBRARIES_VERSION
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
        compilerOptions.languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        // Tests reach the runtime's internal seams (InternalAgentRuntimeApi); agent-a2a and its client don't see kernel-api.
        if (name.endsWith("TestKotlin") && project.name !in setOf("agent-a2a", "agent-a2a-client-official")) {
            compilerOptions.optIn.add("cc.noharry.agent.kernel.api.InternalAgentRuntimeApi")
        }
    }
    tasks.withType<Test>().configureEach {
        val testTmp = project.layout.buildDirectory.dir("tmp/test-tmp/$name").get().asFile
        systemProperty("java.io.tmpdir", testTmp.absolutePath)
        doFirst {
            testTmp.deleteRecursively()
            testTmp.mkdirs()
        }
    }
    plugins.withId("com.vanniktech.maven.publish") { configurePublishing() }
}

/** Every published module: its POM, Maven Central as the target, and signing when a key is configured. */
fun Project.configurePublishing() {
    extensions.configure<MavenPublishBaseExtension> {
        coordinates(group.toString(), name, version.toString())
        publishToMavenCentral()
        if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        pom {
            name.set(project.name)
            description.set(project.description ?: "Android Agent Runtime: ${project.name}")
            url.set(providers.gradleProperty("POM_URL"))
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("NoHarry")
                    name.set("NoHarry")
                    url.set("https://github.com/NoHarry")
                }
            }
            scm {
                url.set(providers.gradleProperty("POM_URL"))
                connection.set("scm:git:https://github.com/NoHarry/android-agent-runtime.git")
                developerConnection.set("scm:git:ssh://git@github.com/NoHarry/android-agent-runtime.git")
            }
        }
    }
}

val checkSourceRules by tasks.registering(SourceRulesTask::class) {
    sources.from(
        fileTree(rootDir) {
            include("**/*.kt", "**/*.kts", "**/*.java")
            exclude("**/build/**", "**/.gradle/**", "**/generated/**", "gradle/wrapper/**", "buildSrc/.gradle/**")
        },
    )
    rootDirectory.set(layout.projectDirectory)
}

tasks.named("check") { dependsOn(checkSourceRules) }

// The Android libraries' API dumps, as the JVM modules': apiDump writes api/<module>.api from the release classes
// (Kotlin and Java), apiCheck (part of check and ciCheck) fails when they differ. The validator's plugin finds Kotlin
// Android code only through the kotlin-android plugin, which AGP 9's built-in Kotlin replaces, so its tasks are set up
// here; they read the same apiValidation settings, and run on a classpath of their own as the plugin's do.
val validation = apiValidation
subprojects {
    plugins.withId("com.android.library") {
        if (name in validation.ignoredProjects || validation.validationDisabled) return@withId
        val apiReader = configurations.create("androidApiReader") {
            isCanBeConsumed = false
        }
        dependencies {
            // What binary-compatibility-validator 0.18.2 reads classes with; follow it when the validator is updated.
            apiReader("org.ow2.asm:asm:9.6")
            apiReader("org.ow2.asm:asm-tree:9.6")
            apiReader("org.jetbrains.kotlin:kotlin-metadata-jvm:${org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)}")
        }
        val dumpName = "${project.name}.api"
        val built = tasks.register<kotlinx.validation.KotlinApiBuildTask>("androidApiBuild") {
            // Looked up when the task is configured, after AGP has made the release tasks: a missing one fails here.
            inputClassesDirs.from(
                tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileReleaseKotlin").flatMap { it.destinationDirectory },
                tasks.named<JavaCompile>("compileReleaseJavaWithJavac").flatMap { it.destinationDirectory },
            )
            runtimeClasspath.from(apiReader)
            outputApiFile.set(layout.buildDirectory.file("api/$dumpName"))
        }
        val check = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
            group = "verification"
            projectApiFile.set(layout.projectDirectory.file("api/$dumpName"))
            generatedApiFile.set(built.flatMap { it.outputApiFile })
        }
        tasks.register<Copy>("apiDump") {
            group = "other"
            from(built.flatMap { it.outputApiFile })
            into(layout.projectDirectory.dir("api"))
        }
        tasks.named("check") { dependsOn(check) }
        rootProject.tasks.named("ciCheck") { dependsOn(check) }
    }
}

/** What CI runs: the source rules, the API dumps, every unit test and the samples' builds. */
val ciCheck by tasks.registering {
    group = "verification"
    description = "Source rules, API dumps, unit tests and sample builds."
    // The JVM modules' build runs their check, which includes the API dump check; the Android libraries add theirs below.
    dependsOn(checkSourceRules)
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("build")) } }
    plugins.withId("com.android.library") {
        rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("testDebugUnitTest")) }
    }
    plugins.withId("com.android.application") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("assembleDebug")) } }
}
