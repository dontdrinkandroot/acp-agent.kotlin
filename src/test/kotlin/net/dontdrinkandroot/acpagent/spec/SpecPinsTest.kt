package net.dontdrinkandroot.acpagent.spec

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private const val SCENARIO_HEADING = "#### Scenario:"
private const val REQUIREMENT_HEADING = "### Requirement:"
private const val PIN_MARKER = "**Pinned by**"

/**
 * Phrases that turn "no test pins this" into a deliberate statement. A scenario may only omit
 * test names behind one of them, so silence can never be mistaken for coverage. Each names the
 * reason: `pinned upstream` relies on the SDK, `manual validation only` needs an environment or a
 * live check, `judgement` rests on a human decision.
 */
private val unpinnablePhrases = listOf("manual validation only", "pinned upstream", "judgement")

private val specsRoot: Path = Path.of("openspec", "specs")
private val compiledTestClassesRoot: Path = Path.of("build", "classes", "kotlin", "test")
private val coverageReport: Path = Path.of("build", "reports", "spec-coverage.md")

/** One parsed capability spec: its requirements. */
private data class CapabilitySpec(val name: String, val requirements: List<Requirement>)

private data class Requirement(val name: String, val scenarios: List<Scenario>) {

    /**
     * A requirement is annotation-anchored once none of its scenarios carries a pin line - its
     * test classes are then the only author of the index. Converting per requirement lets a
     * capability converge one requirement at a time, whenever work touches it.
     */
    val isAnnotationAnchored: Boolean = scenarios.isNotEmpty() && scenarios.none { it.pinLine != null }
}

private data class Scenario(val location: String, val name: String, val pinLine: String?) {
    fun pinnedClassNames(): List<String> =
        pinLine?.let { PIN_BACKTICKED.findAll(it).map { match -> match.groupValues[1] }.toList() }.orEmpty()

    val isExplicitlyUnpinnable: Boolean get() = pinLine != null && unpinnablePhrases.any(pinLine::contains)

    fun pinsAnything(): Boolean = pinnedClassNames().isNotEmpty() || isExplicitlyUnpinnable
}

/** A class-level `@Pins` declaration: the test-side half of the index. */
private data class AnnotationPin(val className: String, val capability: String, val requirement: String) {
    override fun toString(): String = "$className -> $capability: $requirement"
}

/**
 * Keeps the pin index of the spec corpus honest: every requirement is pinned by a test class, and
 * every named class or requirement resolves.
 *
 * A pin lives either as a `- **Pinned by**` line in the spec or as an `@Pins` annotation on the
 * test class. A requirement with no pin lines left is anchored by its annotations alone; one that
 * still carries lines is checked on both halves, so the two cannot drift apart while it converts.
 * Either way a pin must name a class that is compiled and runs at least one test.
 *
 * The check is that a pin *resolves*, never that the test asserts the scenario - a pin is a claim
 * about intent, and how strongly the test holds it stays a review question. The value is in the
 * other direction: a renamed or deleted test class fails the build instead of leaving a stale claim
 * in the source of truth.
 */
class SpecPinsTest {

    @Test
    fun `the corpus is found and non-empty`() {
        assertTrue(
            Files.isDirectory(specsRoot),
            "Spec corpus not found at ${specsRoot.toAbsolutePath()} - the gate would pass vacuously",
        )
        assertTrue(
            capabilities().sumOf { it.requirements.size } > 0,
            "No requirements parsed from $specsRoot - did the heading format change?",
        )
    }

    @Test
    fun `every annotation names an existing requirement`() {
        val requirements = capabilities().flatMap { spec -> spec.requirements.map { spec.name to it.name } }.toSet()
        val dangling = annotationPins().filterNot { (it.capability to it.requirement) in requirements }
        assertTrue(
            dangling.isEmpty(),
            "@Pins naming a requirement the corpus does not have:\n" + dangling.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `every requirement is pinned by a test class`() {
        val pins = annotationPins()
        val unpinned = capabilities().flatMap { spec ->
            spec.requirements
                .filterNot { requirement ->
                    val claimed = pins.any { it.capability == spec.name && it.requirement == requirement.name }
                    claimed || (!requirement.isAnnotationAnchored && requirement.scenarios.any { it.pinsAnything() })
                }
                .map { "${spec.name}: ${it.name}" }
        }
        assertTrue(unpinned.isEmpty(), "Requirements without a pinning test:\n" + unpinned.joinToString("\n") { "  $it" })
    }

    @Test
    fun `every scenario in a line-anchored requirement names the test that pins it`() {
        val unnamed = capabilities().flatMap { spec ->
            spec.requirements
                .filterNot { it.isAnnotationAnchored }
                .flatMap { requirement -> requirement.scenarios.filterNot { it.pinsAnything() } }
                .map { "  ${it.location} - ${it.name}" }
        }
        assertTrue(unnamed.isEmpty(), "Scenarios without a pin:\n" + unnamed.joinToString("\n"))
    }

    @Test
    fun `every pin line resolves to a compiled test class that carries the same annotation`() {
        val compiled = compiledTestClasses()
        val annotated = annotationPins().map { it.className to it }.toSet()
        val problems = capabilities().flatMap { spec ->
            spec.requirements.flatMap { requirement ->
                requirement.scenarios.flatMap { scenario ->
                    scenario.pinnedClassNames().mapNotNull { className ->
                        when {
                            className !in compiled ->
                                "${scenario.location} - ${scenario.name} -> $className (no such compiled test class)"
                            annotated.none { (name, pin) ->
                                name == className && pin.capability == spec.name && pin.requirement == requirement.name
                            } ->
                                "${scenario.location} - ${scenario.name} -> $className (missing @Pins(\"${spec.name}\", \"${requirement.name}\"))"
                            else -> null
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Pin lines that do not resolve:\n" + problems.joinToString("\n") { "  $it" })
    }

    @Test
    fun `every pinned class runs at least one test`() {
        val compiled = compiledTestClasses()
        val named = (
            capabilities().flatMap { spec -> spec.requirements.flatMap { it.scenarios.flatMap { it.pinnedClassNames() } } } +
                annotationPins().map { it.className }
            ).distinct()
        val withoutTests = named
            .mapNotNull { className -> compiled[className]?.takeUnless { hasTestMethods(load(it)) }?.let { className } }
        assertTrue(
            withoutTests.isEmpty(),
            "Pins naming a class that runs no test (abstract base, helper):\n" +
                withoutTests.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the pin index is written for reading`() {
        val pins = annotationPins()
        val unpinnable = mutableListOf<String>()
        val report = buildString {
            appendLine("# Spec coverage")
            appendLine()
            appendLine("Derived from `@Pins` annotations on the test classes. A pin says a class is")
            appendLine("*about* a requirement - never that its assertions prove every scenario.")
            appendLine("A requirement still marked `legacy pin lines` has not been converted yet: it")
            appendLine("is evidenced by its spec lines until work touches it.")
            appendLine()
            capabilities().forEach { spec ->
                appendLine("## ${spec.name}")
                appendLine()
                spec.requirements.forEach { requirement ->
                    val converted = if (requirement.isAnnotationAnchored) "" else " (legacy pin lines)"
                    val classes = pins
                        .filter { it.capability == spec.name && it.requirement == requirement.name }
                        .map { it.className }
                        .sorted()
                    appendLine("- ${requirement.name}$converted")
                    if (classes.isEmpty()) {
                        appendLine("  - no test class pins this")
                        unpinnable += "${spec.name}: ${requirement.name}"
                    } else {
                        appendLine("  - " + classes.joinToString(", "))
                    }
                }
                appendLine()
            }
            appendLine("## Statements without a test")
            appendLine()
            if (unpinnable.isEmpty()) {
                appendLine("None.")
            } else {
                unpinnable.forEach { appendLine("- $it") }
            }
        }
        Files.createDirectories(coverageReport.parent)
        Files.writeString(coverageReport, report)
        assertTrue(Files.size(coverageReport) > 0, "Coverage report not written")
    }

    private fun capabilities(): List<CapabilitySpec> =
        specsRoot.toFile().listFiles { file -> file.isDirectory }
            ?.map { it.resolve("spec.md") }
            ?.filter { it.isFile }
            ?.sortedBy { it.parentFile.name }
            ?.map { parseSpec(it.parentFile.name, it.toPath()) }
            .orEmpty()

    private fun parseSpec(capability: String, specFile: Path): CapabilitySpec {
        val lines = Files.readAllLines(specFile)
        val requirements = mutableListOf<Requirement>()
        var requirement: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith(REQUIREMENT_HEADING)) {
                requirement = line.removePrefix(REQUIREMENT_HEADING).trim()
                requirements += Requirement(requirement, emptyList())
                index++
                continue
            }
            if (!line.startsWith(SCENARIO_HEADING) || requirement == null) {
                index++
                continue
            }
            val blockEnd = (index + 1..lines.lastIndex).firstOrNull { lines[it].startsWith("#") } ?: lines.size
            val pinLine = lines.subList(index + 1, blockEnd).firstOrNull { it.contains(PIN_MARKER) }
            val current = requirements.removeLast()
            requirements += current.copy(
                scenarios = current.scenarios + Scenario(
                    location = "$capability/spec.md:${index + 1}",
                    name = line.removePrefix(SCENARIO_HEADING).trim(),
                    pinLine = pinLine,
                ),
            )
            index = blockEnd
        }
        return CapabilitySpec(capability, requirements)
    }

    private fun annotationPins(): List<AnnotationPin> =
        compiledTestClasses().values.flatMap { fullyQualifiedName ->
            val type = load(fullyQualifiedName)
            type.getAnnotationsByType(Pins::class.java)
                .map { AnnotationPin(type.simpleName, it.capability, it.requirement) }
        }

    /**
     * Simple class name to fully qualified name, so a pin can be inspected without its package.
     * Two classes sharing a simple name would make every pin naming it ambiguous - the very
     * ambiguity this gate exists to prevent - so it fails instead of keeping one of them.
     */
    private fun compiledTestClasses(): Map<String, String> {
        if (!Files.isDirectory(compiledTestClassesRoot)) {
            fail(
                "Compiled test classes not found at ${compiledTestClassesRoot.toAbsolutePath()} - " +
                    "run the gate via the test task",
            )
        }
        val qualifiedNames = compiledTestClassesRoot.toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
            .map { file ->
                compiledTestClassesRoot.relativize(file.toPath()).toString()
                    .removeSuffix(".class")
                    .replace('/', '.')
            }
            .toList()
        val bySimpleName = qualifiedNames.groupBy { it.substringAfterLast('.') }
        val ambiguous = bySimpleName.filterValues { it.size > 1 }.keys
        assertTrue(
            ambiguous.isEmpty(),
            "Test classes share a simple name, so a pin naming one would be ambiguous:\n" +
                ambiguous.sorted().joinToString("\n") { "  $it" },
        )
        return bySimpleName.mapValues { (_, names) -> names.single() }
    }

    private fun load(fullyQualifiedName: String): Class<*> =
        runCatching { Class.forName(fullyQualifiedName, false, javaClass.classLoader) }
            .getOrElse { fail("Cannot inspect $fullyQualifiedName: ${it.message}") }

    /** Any test-framework annotation named `Test`, so the rule survives a JUnit/TestNG switch. */
    private fun hasTestMethods(type: Class<*>): Boolean =
        type.methods.any { method -> method.annotations.any { it.annotationClass.simpleName == "Test" } }
}

private val PIN_BACKTICKED = Regex("`([^`]+)`")
