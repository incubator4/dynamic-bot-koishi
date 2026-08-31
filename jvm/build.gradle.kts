import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.squareup.wire") version "5.4.0"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "top.colter.dynamic"
version = "0.1.0"

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
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.34")
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
}
