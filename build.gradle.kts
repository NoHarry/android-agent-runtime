import com.vanniktech.maven.publish.MavenPublishBaseExtension

// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugin on this classpath sets the compiler
// version for Android and JVM modules alike.
plugins {
    base
    // What an app on the app server declares too (agent-app-server/README.md quotes these lines).
    // guide:start root-plugins
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    // guide:end root-plugins
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
    id("ru.vyarus.animalsniffer") version "2.0.1" apply false
    id("org.jetbrains.dokka-javadoc") version "2.2.0" apply false
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

/** Lint checks that look dependencies up online. */
val ONLINE_LINT_CHECKS = setOf(
    "NewerVersionAvailable", "GradleDependency", "OutdatedLibrary", "RiskyLibrary", "PlaySdkIndexNonCompliant",
    "PlaySdkIndexVulnerability", "PlaySdkIndexGenericIssues", "PlaySdkIndexDeprecated",
)

/** Modules that do not see agent-api, where its opt-in markers are unknown. */
val MODULES_WITHOUT_AGENT_API = setOf("agent-a2a", "agent-a2a-client-official", "agent-app-protocol")

/** The Kotlin standard library the artifacts ask for: the oldest whose API they use (apiVersion 2.1). */
val KOTLIN_LIBRARIES_VERSION = "2.1.21"

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = sdkVersion
}

apiValidation {
    // Samples and the BOM have no API of their own.
    ignoredProjects += listOf("quickstart-jvm", "quickstart-android", "minimal", "extensions", "acp-jvm", "agent-bom")
    ignoredProjects += listOf("agent-app-sample", "agent-app-sample-notes", "agent-app-sample-timer", "agent-app-sample-habits")
    // Public only for the runtime's own modules and tests.
    nonPublicMarkers += "cc.noharry.agent.spi.InternalAgentRuntimeApi"
}

// Unit tests create temp dirs (createTempDirectory, TemporaryFolder, Robolectric) and rarely delete them,
// which left 100k+ folders in the system Temp. Give every Test task its own java.io.tmpdir under the
// module's build directory, emptied before each run, so leftovers stay bounded and `clean` removes them.
subprojects {
    // Interface bodies compile to JVM default methods, so a Java class implementing ModelClient, ToolProvider or a hook
    // writes only what it changes; the DefaultImpls classes stay beside them for Kotlin hosts compiled against 0.4.0 and
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
        if (project.name in setOf("agent-runtime", "agent-runtime-android")) {
            compilerOptions.optIn.add("cc.noharry.agent.spi.InternalAgentRuntimeApi")
            compilerOptions.optIn.add("cc.noharry.agent.spi.ExperimentalAgentRuntimeApi")
        }
        // Tests reach the runtime's internal seams (InternalAgentRuntimeApi); agent-a2a, its client and the app server's
        // protocol don't see agent-api.
        if (name.endsWith("TestKotlin") && project.name !in MODULES_WITHOUT_AGENT_API) {
            compilerOptions.optIn.add("cc.noharry.agent.spi.InternalAgentRuntimeApi")
            compilerOptions.optIn.add("cc.noharry.agent.spi.ExperimentalAgentRuntimeApi")
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

// The JVM modules run on Android from API 23, where Android lint does not look at them: their main classes are checked
// against Android 23's API (Gummy Bears signatures, with what D8 backports). The kernel and the runtime may also use
// what core library desugaring with desugar_jdk_libs_nio 2.1.5 or later adds (java.nio.file), as the README asks hosts
// below API 26 to enable, but only what Android 26 has itself, since hosts from 26 on need not desugar. So may the app
// server's protocol (java.time, for plans' times), since the app server asks its hosts to desugar too. Any other JVM
// module needs no desugaring.
val desugaringModules = setOf("agent-api", "agent-runtime", "agent-app-protocol")
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (project.parent?.name == "samples") return@withId
        apply(plugin = "ru.vyarus.animalsniffer")
        // The published javadoc jar is Dokka's javadoc, as AGP makes it for the Android libraries; CI builds it, so a
        // change that breaks the docs fails before a release does.
        apply(plugin = "org.jetbrains.dokka-javadoc")
        rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("dokkaGeneratePublicationJavadoc")) }
        val signatures = if (project.name in desugaringModules) {
            listOf("gummy-bears-api-23:0.16.0:coreLib2Nio", "gummy-bears-api-26:0.16.0")
        } else {
            listOf("gummy-bears-api-23:0.16.0")
        }
        dependencies { signatures.forEach { add("signature", "com.toasttab.android:$it@signature") } }
        extensions.configure<ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension> {
            sourceSets = listOf(the<SourceSetContainer>()["main"])
        }
    }
}

/** What CI runs: source rules, API dumps, Android API level checks, every unit test and the samples' builds. */
val ciCheck by tasks.registering {
    group = "verification"
    description = "Source rules, API dumps, Android API level checks, unit tests and sample builds."
    // The JVM modules' build runs their check, with the API dump and Android API checks; the Android libraries add
    // theirs and lint below.
    dependsOn(checkSourceRules)
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") { rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("build")) } }
    plugins.withId("com.android.library") {
        // Lint's errors (NewApi among them) fail CI. The checks that judge dependencies by what lint fetches online (newer
        // versions, Google's Maven index, the Play SDK Index) are off, so CI's result depends on the code alone; lint may
        // still fetch those indexes.
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint { disable += ONLINE_LINT_CHECKS }
        }
        rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("testDebugUnitTest"), tasks.named("lintDebug")) }
    }
    plugins.withId("com.android.application") {
        rootProject.tasks.named("ciCheck") { dependsOn(tasks.named("assembleDebug"), tasks.named("testDebugUnitTest")) }
    }
}

// The app server's layering: the core (its protocol and the server) names no plugin's package and carries none of the
// runtime modules only a plugin brings, so a host that lists no plugin merges neither their code nor their manifests
// (the accessibility service, calendar and notification permissions). The first-party plugins are source modules of the
// Bothy app: their packages are named nowhere here; the runtime's optional modules only in their own.
val APP_SERVER_PLUGIN_PACKAGES = mapOf(
    "cc.noharry.agent.appserver.deepseek" to emptyList(),
    "cc.noharry.agent.appserver.protocol.deepseek" to emptyList(),
    "cc.noharry.agent.appserver.a2ui" to emptyList(),
    "cc.noharry.agent.appserver.androiduse" to emptyList(),
    "cc.noharry.agent.androiduse" to listOf("agent-android-use"),
    "cc.noharry.agent.appserver.device" to emptyList(),
    "cc.noharry.agent.device" to listOf("agent-device-capabilities"),
    "cc.noharry.agent.appserver.pages" to emptyList(),
    "cc.noharry.agent.appserver.memory" to emptyList(),
    "cc.noharry.agent.appserver.image" to emptyList(),
    "cc.noharry.agent.appserver.plans" to emptyList(),
    "cc.noharry.agent.appserver.a2a" to emptyList(),
    "cc.noharry.agent.a2a" to listOf("agent-a2a", "agent-a2a-client-official"),
    "cc.noharry.bothy" to emptyList(),
)

/** The runtime modules only a plugin brings: none of them on the core's classpaths. */
val APP_SERVER_CORE_FORBIDDEN = listOf(":agent-a2a", ":agent-a2a-client-official", ":agent-android-use", ":agent-device-capabilities")

val checkAppServerLayering by tasks.registering(PackageRulesTask::class) {
    description = "The app server's core names no plugin and carries none of the runtime modules only a plugin brings."
    sources.from(
        fileTree(rootDir) {
            include("agent-*/src/**/*.kt", "agent-*/src/**/*.java", "samples/**/src/**/*.kt", "samples/**/src/**/*.java")
        },
    )
    rootDirectory.set(layout.projectDirectory)
    allowedIn.set(APP_SERVER_PLUGIN_PACKAGES)
}
tasks.named("ciCheck") { dependsOn(checkAppServerLayering) }
tasks.named("check") { dependsOn(checkAppServerLayering) }

listOf(":agent-app-protocol" to listOf("compileClasspath", "runtimeClasspath"),
    ":agent-app-server" to listOf("debugCompileClasspath", "debugRuntimeClasspath", "releaseRuntimeClasspath"),
).forEach { (path, classpaths) ->
    // After the module is evaluated, when AGP has made its variants' classpaths.
    project(path).afterEvaluate {
        val carried = tasks.register<ForbiddenDependenciesTask>("checkCoreDependencies") {
            modulePath.set(path)
            forbidden.set(APP_SERVER_CORE_FORBIDDEN)
            classpaths.forEach { classpath ->
                found.addAll(configurations.named(classpath).flatMap { it.incoming.resolutionResult.rootComponent.map(::projectsReachedFrom) })
            }
        }
        checkAppServerLayering.configure { dependsOn(carried) }
    }
}

// -PsamplesFromMavenLocal: the apps on the app server build against what publishToMavenLocal wrote, as an app outside
// this repository does: their dependencies on the app server's modules become its published artifacts.
if (providers.gradleProperty("samplesFromMavenLocal").orNull.let { it != null && it != "false" }) {
    val group = providers.gradleProperty("GROUP").get()
    project(":samples").subprojects.filter { it.name.startsWith("agent-app-sample") }.forEach { sample ->
        sample.configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                listOf("agent-app-protocol", "agent-app-server").forEach {
                    substitute(project(":$it")).using(module("$group:$it:$sdkVersion"))
                }
            }
        }
    }
}
