plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

description = "Agent-to-agent (A2A): delegate tasks to remote A2A agents, with a direct JSON-RPC/SSE client or one over " +
    "the official A2A Java SDK (which the app adds), and a local A2A endpoint for the agent itself."

/*
 * Two clients behind one interface (A2aClientModule): the direct one (DirectA2aClientModule: JSON-RPC and SSE over
 * OkHttp, A2A 0.3's methods) and the official one (OfficialA2aClientModule: the official A2A Java SDK 1.1.0.Final,
 * A2A 1.0's methods). The SDK is compileOnly: this jar does not carry it and its POM does not name it, so an app that
 * wants the official client adds it (the README's lines); without it OfficialA2aClientModule() says what is missing.
 * The SDK's common classes are here as source (src/main/java/org/a2aproject/sdk, THIRD_PARTY_NOTICES.md), and the R8
 * rules the SDK needs on Android come in this jar (META-INF/proguard/agent-a2a.pro).
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

/** The official A2A Java SDK the official client runs on, as an app adds it: without its common classes, which are here. */
val officialSdk = listOf("a2a-java-sdk-client", "a2a-java-sdk-http-client-android").map { "org.a2aproject.sdk:$it:1.1.0.Final" }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    officialSdk.forEach { sdk ->
        compileOnly(sdk) { exclude(group = "org.a2aproject.sdk", module = "a2a-java-sdk-common") }
        testImplementation(sdk) { exclude(group = "org.a2aproject.sdk", module = "a2a-java-sdk-common") }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// An app that has not added the official SDK: the module's tests but the official client's run again on a classpath
// without it (testWithoutOfficialSdk, part of check), where the direct client and the server work as before and
// OfficialA2aClientModule() says which artifacts are missing.
val withoutOfficialSdk = tasks.register<Test>("testWithoutOfficialSdk") {
    group = "verification"
    description = "The module's tests but the official client's, on a classpath without the official A2A Java SDK."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter { "org.a2aproject.sdk" !in it.path }
    filter.excludeTestsMatching("cc.noharry.agent.a2a.official.*")
    filter.excludeTestsMatching("org.a2aproject.*")
    systemProperty("agent.withoutOfficialSdk", "true")
}
tasks.named("check") { dependsOn(withoutOfficialSdk) }

// The official client hands the SDK java.util.function callbacks, which Android has from API 24 and core library
// desugaring adds below it (the runtime asks hosts below API 26 to enable it): its classes are checked as the runtime's
// are, against API 23 with desugar_jdk_libs_nio and only what Android 26 has itself; the rest of the module against
// API 23 alone (animalsnifferMain, set up by the root build file).
val officialClientClasses = listOf("cc/noharry/agent/a2a/official/**", "org/a2aproject/**")
val officialClientSignatures = configurations.create("officialClientSignature") { isCanBeConsumed = false }
dependencies {
    listOf("gummy-bears-api-23:0.16.0:coreLib2Nio", "gummy-bears-api-26:0.16.0").forEach {
        officialClientSignatures("com.toasttab.android:$it@signature")
    }
}
val apiLevelCheck = tasks.named<ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer>("animalsnifferMain") {
    exclude(officialClientClasses)
}
val officialClientApiLevelCheck = tasks.register<ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer>("animalsnifferOfficialClient") {
    description = "The official client's classes against Android 23 with core library desugaring, and Android 26."
    source = files(sourceSets.main.get().output.classesDirs).asFileTree.matching { include(officialClientClasses) }
    animalsnifferSignatures = officialClientSignatures
    animalsnifferClasspath = apiLevelCheck.get().animalsnifferClasspath
    // The rest of the module's classes, which the official client uses, are known as they are.
    classpath = apiLevelCheck.get().classpath + files(sourceSets.main.get().output.classesDirs)
    sourcesDirs = apiLevelCheck.get().sourcesDirs
    dependsOn(tasks.named("classes"))
    reports.text.required.set(true)
    reports.text.outputLocation.set(layout.buildDirectory.file("reports/animalsniffer/officialClient.text"))
    reports.csv.required.set(false)
    reports.csv.outputLocation.set(layout.buildDirectory.file("reports/animalsniffer/officialClient.csv"))
}
tasks.named("check") { dependsOn(officialClientApiLevelCheck) }
