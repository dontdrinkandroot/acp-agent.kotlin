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
    fun `run configurations are listed with command and description`() {
        val builder = SystemPromptBuilder(
            "/project",
            { "2026-09-03" },
            runConfigsProvider = {
                listOf(
                    RunConfig("compile", "./gradlew compileKotlin", "Compile main sources (fastest loop)"),
                    RunConfig("test_class", "./gradlew test --tests \"{args}\"", "Run a single test class"),
                    RunConfig("relint", "npm run lint", null),
                )
            },
        )
        val prompt = builder.build(null)
        assertTrue(prompt.contains("Available run configurations"), prompt)
        assertTrue(prompt.contains("- compile: ./gradlew compileKotlin — Compile main sources (fastest loop)"), prompt)
        assertTrue(prompt.contains("- test_class: ./gradlew test --tests \"{args}\" — Run a single test class"), prompt)
        assertTrue(prompt.contains("- relint: npm run lint"), prompt)
        assertTrue(
            prompt.contains("pass `args` only for configurations whose command contains the {args} placeholder"),
            prompt,
        )
    }

    @Test
    fun `run configurations section is omitted when none are defined`() {
        val prompt = builder.build(null)
        assertTrue(!prompt.contains("Available run configurations"), prompt)
        assertTrue(!prompt.contains(".ai/run.json"), prompt)
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