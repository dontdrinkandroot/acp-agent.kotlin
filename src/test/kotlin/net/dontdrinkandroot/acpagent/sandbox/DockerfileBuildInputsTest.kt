package net.dontdrinkandroot.acpagent.sandbox

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Dockerfile composition pins (Kotlin port of `tests/bash/test_dockerfile.bash`):
 *
 * - the builder stage builds from an explicitly COPY'd subset of the repo, so every build
 *   input the Gradle build reads must appear as a COPY source - a missed file (e.g. the
 *   version catalog) fails the image build only, not the local Gradle build;
 * - the `ENTRYPOINT` names the renamed agent script. A stale entrypoint path is the rename's
 *   only untested failure mode: it dies at container start, invisible to `build`.
 */
class DockerfileBuildInputsTest {

    private val dockerfile = Path.of("Dockerfile").toFile().readText()

    /** Every COPY source token of the builder stage (runtime-stage `--from` lines are not inputs). */
    private fun copySources(): List<String> =
        dockerfile.lineSequence()
            .filter { it.startsWith("COPY ") }
            .filterNot { it.contains("--from=") }
            .map { it.removePrefix("COPY ").trim() }
            .flatMap { it.split(Regex("\\s+")).dropLast(1) }
            .toList()

    @Test
    fun `the builder stage copies the wrapper, the catalog and the build scripts`() {
        val sources = copySources()
        for (expected in listOf(
            "gradlew",
            "gradle/wrapper/",
            "gradle/libs.versions.toml",
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "src/",
        )) {
            assertTrue(expected in sources, "expected '$expected' among the builder COPY sources: $sources")
        }
    }

    @Test
    fun `every copied build input exists in the checkout`() {
        // The COPY list is the build-input allowlist; a renamed/moved file must be noticed here
        // instead of in the image build. Directory sources are checked as directories.
        val missing = copySources().filterNot { source ->
            when (source) {
                "gradlew", "gradlew.bat" -> Files.isRegularFile(Path.of(source))
                "src/" -> Files.isDirectory(Path.of(source))
                else -> Files.exists(Path.of(source))
            }
        }
        assertEquals(emptyList(), missing, "builder COPY sources missing from the checkout")
    }

    @Test
    fun `the entrypoint names the renamed agent script`() {
        val entrypointLines = dockerfile.lineSequence().filter { it.startsWith("ENTRYPOINT ") }.toList()
        assertEquals(1, entrypointLines.size, "expected exactly one ENTRYPOINT, was $entrypointLines")
        assertEquals(
            """ENTRYPOINT ["/opt/acp-agent/bin/acp-agent"]""",
            entrypointLines.single(),
            "the entrypoint must point at the renamed distribution (a stale path dies at container start)",
        )
    }
}
