pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // -PsamplesFromMavenLocal: the extensions sample builds against what publishToMavenLocal wrote, as an app would;
        // cc.noharry comes from there only.
        if (providers.gradleProperty("samplesFromMavenLocal").orNull.let { it != null && it != "false" }) {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeGroup("cc.noharry") }
            }
        }
    }
}

rootProject.name = "android-agent-runtime"

// Android-neutral conversation contracts and extension interfaces.
include(":agent-api")
// The runtime (plain JVM) and its Android layer.
include(":agent-runtime")
include(":agent-runtime-android")
// Optional modules.
include(":agent-exec-js")
include(":agent-device-capabilities")
include(":agent-android-use")
include(":agent-a2a")
include(":agent-a2a-client-official")
// One version for all of them.
include(":agent-bom")
// Samples (not published).
include(":samples:quickstart-jvm")
include(":samples:minimal")
include(":samples:extensions")
include(":samples:quickstart-android")
