plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

description = "An A2A client transport over the official A2A Java SDK."

android {
    namespace = "cc.noharry.agent.a2a.official"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":agent-a2a"))
    implementation("org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final")
    implementation("org.a2aproject.sdk:a2a-java-sdk-http-client-android:1.1.0.Final")
    implementation("org.a2aproject.sdk:a2a-java-sdk-spec-grpc:1.1.0.Final")
    implementation("com.google.protobuf:protobuf-java:4.31.1")
    implementation("com.google.protobuf:protobuf-java-util:4.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}

configurations.configureEach {
    exclude(group = "org.a2aproject.sdk", module = "a2a-java-sdk-common")
}
