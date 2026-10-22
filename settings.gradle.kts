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
include(":agent-minimal")
// The runtime (plain JVM) and its Android layer.
include(":agent-vfs")
include(":agent-runtime")
include(":agent-runtime-android")
// Optional modules.
include(":agent-vfs-saf")
include(":agent-exec-js")
include(":agent-device-capabilities")
include(":agent-android-use")
include(":agent-voice")
include(":agent-a2a-client")
include(":agent-a2a-client-direct")
include(":agent-a2a-client-official")
include(":agent-a2a-server")
// One version for all of them.
include(":agent-bom")
// Samples (not published).
include(":samples:quickstart-jvm")
include(":samples:quickstart-android")
