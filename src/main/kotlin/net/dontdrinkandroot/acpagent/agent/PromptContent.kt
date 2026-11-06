package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputImage
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputText

/**
 * Converts prompt content blocks into Responses-API input content parts.
 * Text stays one `input_text` part; any multimodal block becomes its own part.
 * Images are forwarded as base64 data URIs only when the session model accepts
 * image input, otherwise the block degrades to a text placeholder so the model
 * learns it was omitted. Pure mapping, pinned by
 * [AgentSessionPromptContentTest].
 */
internal fun contentBlocksToInputParts(
    blocks: List<ContentBlock>,
    modelSupportsImage: Boolean,
): List<ResponseContentPart> {
    val text = StringBuilder()
    val parts = mutableListOf<ResponseContentPart>()
    fun writeLine(line: String) {
        if (line.isEmpty()) return
        if (text.isNotEmpty()) text.append('\n')
        text.append(line)
    }

    fun flush() {
        if (text.isEmpty()) return
        parts += InputText(text.toString())
        text.clear()
    }

    for (block in blocks) {
        when (block) {
            is ContentBlock.Text -> writeLine(block.text)
            is ContentBlock.ResourceLink -> writeLine(resourceLinkText(block))
            is ContentBlock.Resource -> when (val resource = block.resource) {
                is EmbeddedResourceResource.TextResourceContents ->
                    writeLine("Resource ${resource.uri}:\n${resource.text}")

                is EmbeddedResourceResource.BlobResourceContents -> {
                    flush()
                    parts += InputText("(binary resource ${resource.uri} omitted)")
                }
            }

            is ContentBlock.Image -> {
                flush()
                parts += imagePart(block, modelSupportsImage)
            }

            is ContentBlock.Audio -> {
                flush()
                parts += InputText("(audio content is not supported)")
            }
        }
    }
    flush()
    if (parts.isEmpty()) return listOf(InputText("(empty message)"))
    return parts
}

private fun resourceLinkText(block: ContentBlock.ResourceLink): String {
    val label = block.title?.takeIf { it.isNotBlank() }
        ?: block.name.takeIf { it.isNotBlank() }
        ?: return block.uri
    return "[$label](${block.uri})"
}

private fun imagePart(block: ContentBlock.Image, modelSupportsImage: Boolean): ResponseContentPart {
    if (!modelSupportsImage) {
        return InputText("(image omitted: the selected model does not support image input)")
    }
    if (block.data.isEmpty()) {
        return InputText("(image omitted: no data provided)")
    }
    val mime = block.mimeType.ifEmpty { "image/png" }
    return InputImage("data:$mime;base64,${block.data}")
}
