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
}

// Unit tests create temp dirs (createTempDirectory, TemporaryFolder, Robolectric) and rarely delete them,
// which left 100k+ folders in the system Temp. Give every Test task its own java.io.tmpdir under the
// module's build directory, emptied before each run, so leftovers stay bounded and `clean` removes them.
subprojects {
    // Kotlin 2.2+ compiles interface bodies to JVM default methods; keep the Kotlin 1.9 DefaultImpls-only ABI so the
    // published API stays binary compatible with what hosts compiled against. Language and API level 2.1, with the
    // 2.1 standard library, let hosts on Kotlin 2.0 and later read the artifacts.
    listOf("org.jetbrains.kotlin.jvm", "com.android.library", "com.android.application").forEach { id ->
        pluginManager.withPlugin(id) {
            (extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension).coreLibrariesVersion =
                KOTLIN_LIBRARIES_VERSION
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
        compilerOptions.languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
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

/** What CI runs: the source rules, the API dumps, every unit test and the samples' builds. */
val ciCheck by tasks.registering {
    group = "verification"
    description = "Source rules, API dumps, unit tests and sample builds."
    // The JVM modules' build runs their check, which includes the API dump check.
    dependsOn(checkSourceRules)
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("build")) } }
    plugins.withId("com.android.library") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("testDebugUnitTest")) } }
    plugins.withId("com.android.application") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("assembleDebug")) } }
}
