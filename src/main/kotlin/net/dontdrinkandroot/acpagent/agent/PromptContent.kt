package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIContentPart
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource

/**
 * Converts prompt content blocks into LLM message content. Plain text stays a
 * flat string; any multimodal block switches the message to an array of content
 * parts. Images are forwarded as base64 data URIs only when the session model
 * accepts image input, otherwise the block degrades to a text placeholder so
 * the model learns it was omitted. Pure mapping, pinned by
 * [AgentSessionPromptContentTest].
 */
internal fun contentBlocksToLlmContentTopLevel(
    blocks: List<ContentBlock>,
    modelSupportsImage: Boolean,
): Content {
    val text = StringBuilder()
    val parts = mutableListOf<OpenAIContentPart>()
    fun writeLine(line: String) {
        if (line.isEmpty()) return
        if (text.isNotEmpty()) text.append('\n')
        text.append(line)
    }

    fun flush() {
        if (text.isEmpty()) return
        parts += OpenAIContentPart.Text(text.toString())
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
                    parts += OpenAIContentPart.Text("(binary resource ${resource.uri} omitted)")
                }
            }

            is ContentBlock.Image -> {
                flush()
                parts += imagePart(block, modelSupportsImage)
            }

            is ContentBlock.Audio -> {
                flush()
                parts += OpenAIContentPart.Text("(audio content is not supported)")
            }
        }
    }
    if (parts.isNotEmpty()) {
        flush()
        return Content.Parts(parts)
    }
    if (text.isEmpty()) return Content.Text("(empty message)")
    return Content.Text(text.toString())
}

private fun resourceLinkText(block: ContentBlock.ResourceLink): String {
    val label = block.title?.takeIf { it.isNotBlank() }
        ?: block.name.takeIf { it.isNotBlank() }
        ?: return block.uri
    return "[$label](${block.uri})"
}

private fun imagePart(block: ContentBlock.Image, modelSupportsImage: Boolean): OpenAIContentPart {
    if (!modelSupportsImage) {
        return OpenAIContentPart.Text("(image omitted: the selected model does not support image input)")
    }
    if (block.data.isEmpty()) {
        return OpenAIContentPart.Text("(image omitted: no data provided)")
    }
    val mime = block.mimeType.ifEmpty { "image/png" }
    return OpenAIContentPart.Image(OpenAIContentPart.ImageUrl("data:$mime;base64,${block.data}"))
}