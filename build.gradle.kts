plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "org.example"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.agentclientprotocol:acp:0.30.1")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-cio:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("ai.koog:prompt-executor-openrouter-client:1.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("io.github.oshai:kotlin-logging:8.0.4")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("net.dontdrinkandroot.acpagent.MainKt")
}

tasks.test {
    dependsOn("installDist")
    systemProperty(
        "acp.agent.binary",
        layout.buildDirectory.file("install/acp-agent.kotlin/bin/acp-agent.kotlin").get().asFile.absolutePath,
    )
}
