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
include(":agent-acp")
// One version for all of them.
include(":agent-bom")
// The app server (experimental): its protocol and the server with its plugin kernel. Its first-party plugins are source
// modules of the Bothy app, not published.
include(":agent-app-protocol")
include(":agent-app-server")
// Samples (not published).
include(":samples:quickstart-jvm")
include(":samples:acp-jvm")
include(":samples:minimal")
include(":samples:extensions")
include(":samples:quickstart-android")
// Apps on the app server.
include(":samples:agent-app-sample")
include(":samples:agent-app-sample-notes")
include(":samples:agent-app-sample-timer")
include(":samples:agent-app-sample-habits")
