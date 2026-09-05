package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Black-box AGENTS.md injection and multimodal prompt conversion: the session
 * cwd's AGENTS.md is re-read into the system prompt, an image content block is
 * converted to a base64 data URI (the mock model advertises image input), and a
 * text resource is inlined.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2ePromptCapabilitiesTest : E2eAgentTest() {

    @Test
    fun `e2e project instructions injection and multimodal prompt conversion`() = runBlocking {
        withE2eAgent("multimodal", { projectDir ->
            projectDir.resolve("AGENTS.md").writeText("Follow the repository project rules.")
            MockOpenAiServer("unused", textOnly = true, imageSupport = true)
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(
                    session,
                    listOf(
                        ContentBlock.Text("Analyze this"),
                        ContentBlock.Image("aGVsbG8=", "image/png"),
                        ContentBlock.Resource(
                            EmbeddedResourceResource.TextResourceContents("embedded snippet", "file:///embed.txt")
                        ),
                    ),
                )
                assertEndTurn(events)
                val body = requireNotNull(llmMock.lastRequestBody) { "no chat request captured" }
                assertTrue(body.contains("## Project Instructions (from AGENTS.md)"), "AGENTS.md section missing")
                assertTrue(body.contains("Follow the repository project rules."), "AGENTS.md content missing")
                assertTrue(body.contains("data:image/png;base64,aGVsbG8="), "image must become a data URI")
                assertTrue(body.contains("embedded snippet"), "text resource must be inlined")
                assertTrue(body.contains("file:///embed.txt"), "resource uri must be rendered")
                println("[ok] AGENTS.md injected into the system prompt + image/resource converted")
            } finally {
                connection.close()
            }
        }
    }
}