package net.dontdrinkandroot.acpagent.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class SystemPromptDumpTest {

    @Test
    fun `review dump renders the prompt sections but never the AGENTS md instructions`() {
        val dir = Files.createTempDirectory("acp-agent-prompt-dump").toFile()
        dir.resolve("AGENTS.md").writeText("Project rules here")
        val prompt = reviewSystemPrompt(dir.absolutePath, emptyMap())
        assertTrue(prompt.contains("Session working directory: ${dir.absolutePath}"), prompt)
        assertTrue(prompt.contains("- plan: read-only"), prompt)
        assertTrue(prompt.contains("Excluded files:"), prompt)
        assertTrue(!prompt.contains("Project Instructions"), prompt)
        assertTrue(!prompt.contains("Project rules here"), prompt)
    }
}
