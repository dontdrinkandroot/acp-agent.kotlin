package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIContentPart
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionPromptContentTest {

    @Test
    fun `text only content stays a flat text content`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Text("hello"), ContentBlock.Text("world")),
            modelSupportsImage = true,
        )
        assertEquals("hello\nworld", content.text())
    }

    @Test
    fun `image on image-capable model becomes a data uri part`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Text("look"), ContentBlock.Image("aGVsbG8=", "image/png")),
            modelSupportsImage = true,
        )
        assertTrue(content is Content.Parts, "expected content parts, got ${content::class}")
        val parts = (content as Content.Parts).value
        assertEquals(2, parts.size)
        assertEquals("look", (parts[0] as OpenAIContentPart.Text).text)
        val image = parts[1] as OpenAIContentPart.Image
        assertEquals("data:image/png;base64,aGVsbG8=", image.imageUrl.url)
    }

    @Test
    fun `image degrades to a placeholder when the model does not support images`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Image("aGVsbG8=", "image/png")),
            modelSupportsImage = false,
        )
        assertTrue(content is Content.Parts, "expected content parts, got ${content::class}")
        val part = (content as Content.Parts).value.single() as OpenAIContentPart.Text
        assertTrue(part.text.contains("does not support image input"), part.text)
    }

    @Test
    fun `image without data degrades to a placeholder`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Image("", "image/png")),
            modelSupportsImage = true,
        )
        val part = (content as Content.Parts).value.single() as OpenAIContentPart.Text
        assertEquals("(image omitted: no data provided)", part.text)
    }

    @Test
    fun `image without mime type defaults to png`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Image("aGVsbG8=", "")),
            modelSupportsImage = true,
        )
        val image = (content as Content.Parts).value.single() as OpenAIContentPart.Image
        assertEquals("data:image/png;base64,aGVsbG8=", image.imageUrl.url)
    }

    @Test
    fun `text resource is inlined with its uri`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(
                ContentBlock.Resource(
                    EmbeddedResourceResource.TextResourceContents(
                        "inlined text",
                        "file:///x.txt"
                    )
                )
            ),
            modelSupportsImage = false,
        )
        assertEquals("Resource file:///x.txt:\ninlined text", content.text())
    }

    @Test
    fun `blob resource degrades to a placeholder`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Resource(EmbeddedResourceResource.BlobResourceContents("AAAA", "file:///x.bin"))),
            modelSupportsImage = false,
        )
        val part = (content as Content.Parts).value.single() as OpenAIContentPart.Text
        assertEquals("(binary resource file:///x.bin omitted)", part.text)
    }

    @Test
    fun `resource link is rendered as markdown link`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.ResourceLink("the file", "file:///x.txt")),
            modelSupportsImage = false,
        )
        assertEquals("[the file](file:///x.txt)", content.text())
    }

    @Test
    fun `audio degrades to a placeholder`() {
        val content = contentBlocksToLlmContentTopLevel(
            listOf(ContentBlock.Audio("AAAA", "audio/wav")),
            modelSupportsImage = false,
        )
        val part = (content as Content.Parts).value.single() as OpenAIContentPart.Text
        assertEquals("(audio content is not supported)", part.text)
    }

    @Test
    fun `empty prompt becomes an empty message placeholder`() {
        val content = contentBlocksToLlmContentTopLevel(emptyList(), modelSupportsImage = false)
        assertEquals("(empty message)", content.text())
    }

    @Test
    fun `loadAgentsInstructions reads the file from the session cwd`() {
        val dir = Files.createTempDirectory("acp-agent-agentsmd").toFile()
        val file = dir.resolve("AGENTS.md")
        file.writeText("Project rules here")
        val instructions = loadAgentsInstructions(dir.absolutePath)
        assertEquals(AgentsInstructions(file.absolutePath, "Project rules here"), instructions)
    }

    @Test
    fun `loadAgentsInstructions returns null when the file is missing`() {
        val dir = Files.createTempDirectory("acp-agent-agentsmd").toFile()
        assertNull(loadAgentsInstructions(dir.absolutePath))
    }

    @Test
    fun `instructionsSection renders the loaded path and content`() {
        val section = instructionsSection(AgentsInstructions("/project/AGENTS.md", "rule one\nrule two"))
        assertTrue(section.contains("## Project Instructions (from AGENTS.md)"), section)
        assertTrue(section.contains("Loaded from: /project/AGENTS.md"), section)
        assertTrue(section.contains("rule one"), section)
    }

    @Test
    fun `instructionsSection is empty for missing or blank instructions`() {
        assertEquals("", instructionsSection(null))
        assertEquals("", instructionsSection(AgentsInstructions("/project/AGENTS.md", "   \n ")))
    }
}
