import java.io.File
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.versions)
    application
}

group = "org.example"
version = "0.1.0-SNAPSHOT"

val gitCommit: String = System.getenv("GIT_SHA")
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
            .standardOutput.asText.get().trim()
    }.getOrNull()
    ?: "unknown"

val gitVersion: String = if (gitCommit != "unknown" && gitCommit != System.getenv("GIT_SHA") && runCatching {
        providers.exec { commandLine("git", "status", "--porcelain") }
            .standardOutput.asText.get().isNotBlank()
    }.getOrDefault(false)
) {
    "$gitCommit-dirty"
} else {
    gitCommit
}

val generateGitProperties = tasks.register("generateGitProperties") {
    val commit = gitVersion
    val outputDir = layout.buildDirectory.dir("generated/git-properties")
    outputs.dir(outputDir)
    inputs.property("gitCommit", commit)
    doLast {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        File(dir, "git.properties").writeText("git.commit=$commit\n")
    }
}

sourceSets.main.get().resources.srcDir(generateGitProperties)

// Resolves the -sources jars of every main and test classpath dependency into
// build/library-sources for browsing library code at the exact resolved versions
// (e.g. to verify SDK contract checks against sources instead of guessing docs).
// Uses the current ArtifactView-withVariantReselection API; the often-copied
// createArtifactResolutionQuery recipe is legacy (maintenance mode, slated for
// removal in Gradle 9.x, gradle/gradle#26365).
val sourcesViewAttributes: ArtifactView.ViewConfiguration.() -> Unit = {
	withVariantReselection()
	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
		attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
		attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
		attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
	}
}
val downloadLibrarySources = tasks.register<Sync>("downloadLibrarySources") {
	group = "documentation"
	description = "Resolves -sources jars for the main and test classpaths into build/library-sources."
	from(
		configurations.runtimeClasspath.get().incoming.artifactView(sourcesViewAttributes).files,
		configurations.testRuntimeClasspath.get().incoming.artifactView(sourcesViewAttributes).files,
	)
	into(layout.buildDirectory.dir("library-sources"))
}
val unpackLibrarySources = tasks.register<Sync>("unpackLibrarySources") {
	group = "documentation"
	description = "Unpacks the -sources jars into build/library-sources-unpacked/<artifact>/ for browsing."
	dependsOn(downloadLibrarySources)
	for (jar in configurations.runtimeClasspath.get().incoming.artifactView(sourcesViewAttributes).files +
		configurations.testRuntimeClasspath.get().incoming.artifactView(sourcesViewAttributes).files
	) {
		from(zipTree(jar)) { into(jar.name.removeSuffix("-sources.jar")) }
	}
	into(layout.buildDirectory.dir("library-sources-unpacked"))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.acp)
    implementation(libs.mcp.kotlin.sdk.client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.encoding)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koog.openrouter.client)
    implementation(libs.uuid.creator)
    implementation(libs.slf4j.simple)
    implementation(libs.kotlin.logging)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("net.dontdrinkandroot.acpagent.MainKt")
}

tasks.named<Sync>("installDist") {
    val destination = layout.buildDirectory.file("install/acp-agent.kotlin").get().asFile
    doLast {
        destination.walkTopDown().forEach { it.setReadable(true, false) }
    }
}

tasks.test {
    dependsOn("installDist")
    systemProperty(
        "acp.agent.binary",
        layout.buildDirectory.file("install/acp-agent.kotlin/bin/acp-agent.kotlin").get().asFile.absolutePath,
    )
}

// Black-box shell suite pinning the docker launcher composition (tests/bash);
// always runs, so the "build green" definition of done includes it.
val testScripts = tasks.register<Exec>("testScripts") {
    group = "verification"
    description = "Runs the shell test suite (tests/bash/run-all) pinning the docker launcher composition"
    commandLine("tests/bash/run-all")
    inputs.dir("tests/bash")
    inputs.file("ddr-acp-agent-docker")
    inputs.file("ddr-acp-agent")
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(testScripts)
}

// Dependency updates: informational report only (run config dependency_updates),
// deliberately not wired into check. Only stable candidates are suggested - a
// non-stable candidate is rejected unless the current version is itself non-stable
// (ben-manes/versions README recommendation). The plugin's -Drevision=release flag
// was removed in 0.52 and is silently ignored, so the filter lives here instead.
tasks.withType<DependencyUpdatesTask>().configureEach {
    gradleReleaseChannel = "current"
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}

private fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    return stableKeyword.not() && Regex("^[0-9,.v-]+(-r)?$").matches(version).not()
}
