package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionModeId
import kotlin.test.Test
import kotlin.test.assertTrue

class SystemPromptTest {

    private val builder = SystemPromptBuilder("/project", { "2026-09-03" })

    @Test
    fun `prompt embeds cwd, date, build hash and mode description`() {
        val prompt = builder.build(SessionModeId("build"), null)
        assertTrue(prompt.contains("Session working directory: /project"), prompt)
        assertTrue(prompt.contains("Today's date: 2026-09-03"), prompt)
        assertTrue(prompt.contains("Agent build: "), prompt)
        assertTrue(prompt.contains("Current mode: build. You may read, write, move and delete files"), prompt)
    }

    @Test
    fun `bash and plan modes render their own descriptions`() {
        val bash = builder.build(SessionModeId("bash"), null)
        assertTrue(bash.contains("run shell commands via the 'bash' tool"), bash)
        val plan = builder.build(SessionModeId("plan"), null)
        assertTrue(plan.contains("You are in PLAN mode. You must not modify files"), plan)
    }

    @Test
    fun `unknown mode falls back to the plan description`() {
        val prompt = builder.build(SessionModeId("research"), null)
        assertTrue(prompt.contains("You are in PLAN mode"), prompt)
    }

    @Test
    fun `operating rules cover edit-file deltas, source-derived test expectations and the run tool`() {
        val prompt = builder.build(SessionModeId("build"), null)
        assertTrue(prompt.contains("Modify existing code with `edit_file` deltas"), prompt)
        assertTrue(prompt.contains("derive the expectation from the code being tested"), prompt)
        assertTrue(prompt.contains("Prefer the `run` tool's named configurations"), prompt)
    }

    @Test
    fun `project instructions are appended after the rules`() {
        val prompt = builder.build(
            SessionModeId("plan"),
            AgentsInstructions("/project/AGENTS.md", "Project rules here"),
        )
        assertTrue(prompt.contains("## Project Instructions (from AGENTS.md)"), prompt)
        assertTrue(prompt.endsWith("Project rules here"), prompt)
    }

    @Test
    fun `prompt without instructions omits the project instructions section`() {
        val prompt = builder.build(SessionModeId("plan"), null)
        assertTrue(!prompt.contains("Project Instructions"), prompt)
    }
}
