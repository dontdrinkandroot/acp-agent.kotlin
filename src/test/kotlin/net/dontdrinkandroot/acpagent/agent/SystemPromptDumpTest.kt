package net.dontdrinkandroot.acpagent.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemPromptDumpTest {

    @Test
    fun `review dump renders every section from example values, not the environment`() {
        val prompt = reviewSystemPrompt()
        assertTrue(prompt.contains("Session working directory: /home/dev/projects/example-project"), prompt)
        assertTrue(prompt.contains("* plan: read-only"), prompt)
        // The example data is invented, so every optional section renders.
        assertTrue(prompt.contains("## Trusted paths"), prompt)
        assertTrue(prompt.contains("* /srv/data"), prompt)
        assertTrue(prompt.contains("* /srv/scratch"), prompt)
        assertTrue(prompt.contains("## Excluded files"), prompt)
        assertTrue(prompt.contains("secrets/**"), prompt)
        assertTrue(prompt.contains("## Available run configurations"), prompt)
        // The project instructions are never part of the dump.
        assertTrue(!prompt.contains("Project Instructions"), prompt)
    }
}
