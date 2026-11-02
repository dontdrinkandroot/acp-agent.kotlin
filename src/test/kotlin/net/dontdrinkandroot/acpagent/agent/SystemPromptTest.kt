package net.dontdrinkandroot.acpagent.agent

import net.dontdrinkandroot.acpagent.tools.RunConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class SystemPromptTest {

    private val builder = SystemPromptBuilder("/project", { "2026-09-03" })

    @Test
    fun `prompt embeds cwd, date, build hash and the static modes table`() {
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Session working directory: /project"), prompt)
        assertTrue(prompt.contains("Today's date: 2026-09-03"), prompt)
        assertTrue(prompt.contains("Agent build: "), prompt)
        assertTrue(prompt.contains("- plan: read-only"), prompt)
        assertTrue(prompt.contains("- build: read-write"), prompt)
        assertTrue(prompt.contains("- bash: build plus the permission-gated bash tool"), prompt)
    }

    @Test
    fun `prompt is mode-invariant and holds no current mode statement`() {
        val prompt = builder.build(null)
        assertTrue(!prompt.contains("Current mode"), prompt)
        // The current mode and the available tools live in the conversation's
        // mode status messages, not in the prompt.
        assertTrue(
            prompt.contains("The current mode is stated in a status message in the conversation"),
            prompt,
        )
    }

    @Test
    fun `operating rules cover edit-file deltas, source-derived test expectations and the run tool`() {
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Modify existing code with `edit_file` deltas"), prompt)
        assertTrue(prompt.contains("derive the expectation from the code being tested"), prompt)
        assertTrue(prompt.contains("Prefer the `run` tool's named configurations"), prompt)
    }

    @Test
    fun `operating rules pin run config durability and the bash-mode criteria`() {
        val prompt = builder.build(null)
        // The rules section must be path-free: the storage location is an
        // implementation detail (the separate exclusion section names it).
        val rules = prompt.substringBefore("Excluded files:")
        assertTrue(rules.contains("durable, shared project workflows"), prompt)
        assertTrue(rules.contains("only for commands worth repeating"), prompt)
        assertTrue(rules.contains("one-off"), prompt)
        assertTrue(!rules.contains(".ai/run.json"), prompt)
        // The prohibition is absolute: no mint-then-clean-up escape hatch.
        assertTrue(!rules.contains("delete it"), prompt)
        assertTrue(!rules.contains("once it has served"), prompt)
        // Bash-mode criteria: durable-config unusable/uncreatable or awkwardly complicated.
        assertTrue(rules.contains("no durable run configuration can be used or created"), prompt)
        assertTrue(rules.contains("awkwardly complicated"), prompt)
        assertTrue(rules.contains("ask the user for bash mode"), prompt)
        // The new criteria absorbs the old generic mode-gap clause.
        assertTrue(!rules.contains("attempting a workaround"), prompt)
    }

    @Test
    fun `run configurations are listed with command and description`() {
        val builder = SystemPromptBuilder(
            "/project",
            { "2026-09-03" },
            runConfigsProvider = {
                listOf(
                    RunConfig("compile", "./gradlew compileKotlin", "Compile main sources (fastest loop)"),
                    RunConfig("test_class", "./gradlew test --tests [args]", "Run a single test class"),
                    RunConfig("relint", "npm run lint", null),
                )
            },
        )
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Available run configurations"), prompt)
        assertTrue(prompt.contains("- compile: ./gradlew compileKotlin — Compile main sources (fastest loop)"), prompt)
        assertTrue(prompt.contains("- test_class: ./gradlew test --tests [args] — Run a single test class"), prompt)
        assertTrue(prompt.contains("- relint: npm run lint"), prompt)
        assertTrue(
            prompt.contains("pass `args` (an array; one element = one argument) only for configurations whose command contains the [args] slot"),
            prompt,
        )
    }

    @Test
    fun `excluded files section names both default rules and the dedicated run-config tools`() {
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Excluded files:"), prompt)
        assertTrue(prompt.contains(".env*.local"), prompt)
        assertTrue(prompt.contains(".ai/run.json"), prompt)
        assertTrue(prompt.contains("create_run_config"), prompt)
    }

    @Test
    fun `excluded files section is omitted without rules`() {
        val builder = SystemPromptBuilder("/project", { "2026-09-03" }, excludedFileGlobs = emptyList())
        assertTrue(!builder.build(null).contains("Excluded files:"), builder.build(null))
    }

    @Test
    fun `run configurations section is omitted when none are defined`() {
        val prompt = builder.build(null)
        assertTrue(!prompt.contains("Available run configurations"), prompt)
        assertTrue(!prompt.contains("pass `args` (an array"), prompt)
    }

    @Test
    fun `trusted read paths section is present when configured and states read-only semantics`() {
        val builder = SystemPromptBuilder(
            "/project",
            { "2026-09-03" },
            trustedReadPaths = listOf("/srv/data", "/mnt/scratch"),
        )
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Trusted read paths"), prompt)
        assertTrue(prompt.contains("/srv/data, /mnt/scratch"), prompt)
        assertTrue(prompt.contains("writes and mutations still require permission"), prompt)
    }

    @Test
    fun `trusted read paths section is omitted when none are configured`() {
        val prompt = builder.build(null)
        assertTrue(!prompt.contains("Trusted read paths"), prompt)
    }

    @Test
    fun `project instructions are appended after the rules`() {
        val prompt = builder.build(
            AgentsInstructions("/project/AGENTS.md", "Project rules here"),
        )
        assertTrue(prompt.contains("## Project Instructions (from AGENTS.md)"), prompt)
        assertTrue(prompt.endsWith("Project rules here"), prompt)
    }

    @Test
    fun `prompt without instructions omits the project instructions section`() {
        val prompt = builder.build(null)
        assertTrue(!prompt.contains("Project Instructions"), prompt)
    }
}