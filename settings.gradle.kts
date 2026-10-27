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
    }
}

rootProject.name = "android-agent-runtime"

// The kernel: Android-neutral contracts and the agent loop.
include(":agent-kernel-api")
include(":agent-kernel-core")
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
include(":samples:quickstart-android")
