import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.squareup.wire") version "5.4.0"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "com.incubator4.dynamic"
version = dbkGitVersion()

val generateDbkVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/dbkVersion")
    inputs.property("dbkVersion", version.toString())
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("com/incubator4/dynamic/koishi/DbkVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.incubator4.dynamic.koishi

            internal const val DBK_APP_VERSION: String = ${version.toString().asKotlinStringLiteral()}

            """.trimIndent() + "\n",
        )
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateDbkVersion)
}

tasks.processResources {
    inputs.property("dbkVersion", version.toString())
    filesMatching("plugin.yml") {
        expand("dbkVersion" to version.toString())
    }
}

fun dbkGitVersion(): String {
    val override = System.getenv("DBK_VERSION")?.trim()
    if (!override.isNullOrEmpty()) return override
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--abbrev=7", "--dirty")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0 && output.isNotEmpty() && !output.startsWith("fatal:")) {
            output
        } else {
            "0.0.0-dev"
        }
    } catch (_: Exception) {
        "0.0.0-dev"
    }
}

fun String.asKotlinStringLiteral(): String =
    buildString {
        append('"')
        for (ch in this@asKotlinStringLiteral) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\u0024")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }

repositories {
    mavenLocal()
    maven(url = "https://maven.aliyun.com/repository/public")
    mavenCentral()
}

wire {
    sourcePath {
        srcDir(rootDir.resolve("../proto"))
        include("dbk/v1/**")
    }
    kotlin {
        rpcRole = "none"
        enumMode = "enum_class"
        // Wire 5.4 has boxed OneOf; sealed_class oneofMode arrives in Wire 7.
        boxOneOfsMinSize = 1
    }
}

configurations.named("testRuntimeClasspath") {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.18")
}

dependencies {
    val coroutinesVersion = "1.11.0"
    val coreVersion = "0.0.4"
    val kotlinLoggingVersion = "8.0.4"
    val log4jVersion = "2.26.0"
    val slf4jVersion = "2.0.18"
    val wireVersion = "5.4.0"

    compileOnly("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("com.squareup.wire:wire-runtime:$wireVersion")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    compileOnly("org.apache.logging.log4j:log4j-api:$log4jVersion")

    testImplementation(kotlin("test"))
    testImplementation("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    testImplementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation("org.apache.logging.log4j:log4j-api:$log4jVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testRuntimeOnly("org.slf4j:slf4j-api:$slf4jVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.6.3")
    testRuntimeOnly("org.apache.logging.log4j:log4j-to-slf4j:$log4jVersion")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    jvmToolchain(21)
    sourceSets.getByName("main").kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/dbkVersion"),
    )
}
