package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputImage
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentSessionPromptContentTest {

    private fun text(parts: List<ResponseContentPart>): String =
        parts.filterIsInstance<InputText>().joinToString("\n") { it.text }

    @Test
    fun `text only content stays a single text part`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Text("hello"), ContentBlock.Text("world")),
            modelSupportsImage = true,
        )
        assertEquals("hello\nworld", text(parts))
        assertEquals(1, parts.size)
    }

    @Test
    fun `image on image-capable model becomes a data uri part`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Text("look"), ContentBlock.Image("aGVsbG8=", "image/png")),
            modelSupportsImage = true,
        )
        assertEquals(2, parts.size)
        assertEquals("look", assertIs<InputText>(parts[0]).text)
        assertEquals("data:image/png;base64,aGVsbG8=", assertIs<InputImage>(parts[1]).imageUrl)
    }

    @Test
    fun `image degrades to a placeholder when the model does not support images`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Image("aGVsbG8=", "image/png")),
            modelSupportsImage = false,
        )
        val part = assertIs<InputText>(parts.single())
        assertTrue(part.text.contains("does not support image input"), part.text)
    }

    @Test
    fun `image without data degrades to a placeholder`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Image("", "image/png")),
            modelSupportsImage = true,
        )
        assertEquals("(image omitted: no data provided)", assertIs<InputText>(parts.single()).text)
    }

    @Test
    fun `image without mime type defaults to png`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Image("aGVsbG8=", "")),
            modelSupportsImage = true,
        )
        assertEquals("data:image/png;base64,aGVsbG8=", assertIs<InputImage>(parts.single()).imageUrl)
    }

    @Test
    fun `text resource is inlined with its uri`() {
        val parts = contentBlocksToInputParts(
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
        assertEquals("Resource file:///x.txt:\ninlined text", text(parts))
    }

    @Test
    fun `blob resource degrades to a placeholder`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Resource(EmbeddedResourceResource.BlobResourceContents("AAAA", "file:///x.bin"))),
            modelSupportsImage = false,
        )
        assertEquals("(binary resource file:///x.bin omitted)", assertIs<InputText>(parts.single()).text)
    }

    @Test
    fun `resource link is rendered as markdown link`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.ResourceLink("the file", "file:///x.txt")),
            modelSupportsImage = false,
        )
        assertEquals("[the file](file:///x.txt)", text(parts))
    }

    @Test
    fun `audio degrades to a placeholder`() {
        val parts = contentBlocksToInputParts(
            listOf(ContentBlock.Audio("AAAA", "audio/wav")),
            modelSupportsImage = false,
        )
        assertEquals("(audio content is not supported)", assertIs<InputText>(parts.single()).text)
    }

    @Test
    fun `empty prompt becomes an empty message placeholder`() {
        val parts = contentBlocksToInputParts(emptyList(), modelSupportsImage = false)
        assertEquals("(empty message)", text(parts))
    }
}
