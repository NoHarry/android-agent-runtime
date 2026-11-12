plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

description = "Calendar, notification and media tools for the agent; on the app server, as its plugin."

/*
 * The device's calendar, notifications and media as tool providers (cc.noharry.agent.device), for any host. Its plugin
 * for the app server (cc.noharry.agent.device.appserver, DeviceToolsPlugin) is compiled against agent-app-server without
 * carrying it: the module's published dependencies stay agent-api's, and its manifest declares the plugin, which the
 * server loads when the app has it (PluginDiscovery). A host with the runtime alone never loads the plugin's class
 * (testWithoutAppServer runs the module's tests where the server is missing).
 */
android {
    namespace = "cc.noharry.agent.device"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The plugin uses the app server, experimental (ExperimentalAppServerApi), and what it keeps for plugins
// (AppServerPluginApi); its tests start it on the server's plugin host and read the server's tool rows, public only for
// tests (InternalAppServerApi).
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
        optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}

dependencies {
    api(project(":agent-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // The plugin's, compiled against and never carried: an app that wants it on the server has the server itself.
    compileOnly(project(":agent-app-server"))

    testImplementation(project(":agent-app-server"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
