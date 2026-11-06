package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The directory permission tiers of `config.json` (issue #50), end to end:
 *
 * - a read under `permissions.readableDirectories` runs prompt-free,
 * - a write under `permissions.writableDirectories` runs prompt-free,
 * - a write under a *readable* directory still prompts (the tiers are
 *   directional),
 * - `permissions.unlistedPaths = "deny"` refuses an unlisted target without a
 *   prompt, with a refusal naming the missing config entry.
 *
 * The config file is placed at the *implicit* location
 * (`$XDG_CONFIG_HOME/ddr-acp-agent/config.json`), so the default path is pinned
 * black-box; the explicit file-name override is unit-pinned in `ConfigTest`.
 */
class E2eDirectoryTiersTest : E2eAgentTest() {

    /** A config directory holding `config.json`, ready to be used as `XDG_CONFIG_HOME`. */
    private fun configHome(json: String): File =
        Files.createTempDirectory("acp-e2e-config-home").toFile().apply {
            deleteOnExit()
            resolve("ddr-acp-agent").mkdirs()
            resolve("ddr-acp-agent").resolve("config.json").writeText(json)
        }

    private fun directoryConfig(readable: File, writable: File): String =
        """
        {
          "permissions": {
            "readableDirectories": ["${readable.absolutePath}"],
            "writableDirectories": ["${writable.absolutePath}"],
            "unlistedPaths": "prompt"
          }
        }
        """.trimIndent()

    @Test
    fun `readable and writable tiers allow prompt-free access and stay directional`() = runBlocking {
        val readableDir = Files.createTempDirectory("acp-e2e-tier-readable").toFile()
        val writableDir = Files.createTempDirectory("acp-e2e-tier-writable").toFile()
        try {
            val readTarget = readableDir.resolve("notes.txt").apply { writeText("tier content") }
            val writeTarget = writableDir.resolve("written.txt")
            val readableWriteTarget = readableDir.resolve("denied.txt")
            withE2eAgent(
                "tiers",
                MockOpenAiServer(
                    "unused",
                    // 1st call: read in the readable tier, 2nd: write in the writable
                    // tier, 3rd: write in the *readable* tier (must prompt), 4th: text.
                    toolCalls = listOf(
                        MockToolCall("read_file", readFileArgs(readTarget.absolutePath)),
                        MockToolCall(
                            "write_file",
                            buildJsonObject {
                                put("path", writeTarget.absolutePath)
                                put("content", "tier write")
                            },
                        ),
                        MockToolCall(
                            "write_file",
                            buildJsonObject {
                                put("path", readableWriteTarget.absolutePath)
                                put("content", "must prompt")
                            },
                        ),
                        null,
                    ),
                ),
            ) {
                val connection = connect(
                    extraEnv = mapOf(
                        "XDG_CONFIG_HOME" to configHome(directoryConfig(readableDir, writableDir)).absolutePath,
                    )
                )
                try {
                    connection.client.initialize(testClientInfo())
                    val permissive = TestClientOperations(permissionOptionId = "reject_once")
                    val session = newSession(connection.client, projectDir, permissive)
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    collectPrompt(session, listOf(ContentBlock.Text("Use the tiers")))

                    assertEquals(
                        1,
                        permissive.permissionRequests.size,
                        "only the write inside the *readable* tier may prompt; the readable-tier read " +
                                "and the writable-tier write must run prompt-free",
                    )
                    assertTrue(
                        writeTarget.exists() && writeTarget.readText() == "tier write",
                        "the write inside the writable tier must have executed",
                    )
                    val firstRequest = llmMock.requestBodies.first()
                    assertTrue(
                        firstRequest.contains("## Trusted paths") && firstRequest.contains(readableDir.absolutePath),
                        "the system prompt must surface the configured tiers",
                    )
                    println("[ok] readable/writable tiers gate the file tools directionally")
                } finally {
                    connection.close()
                }
            }
        } finally {
            readableDir.deleteRecursively()
            writableDir.deleteRecursively()
        }
    }

    @Test
    fun `unlisted paths set to deny refuses without a prompt and names the config entry`() = runBlocking {
        val unlistedDir = Files.createTempDirectory("acp-e2e-tier-unlisted").toFile()
        try {
            val target = unlistedDir.resolve("notes.txt").apply { writeText("unlisted content") }
            withE2eAgent(
                "tiers-deny",
                MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(target.absolutePath))),
            ) {
                val connection = connect(
                    extraEnv = mapOf(
                        "XDG_CONFIG_HOME" to configHome(
                            """
                            {
                              "permissions": {
                                "readableDirectories": [],
                                "writableDirectories": [],
                                "unlistedPaths": "deny"
                              }
                            }
                            """.trimIndent()
                        ).absolutePath,
                    )
                )
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    collectPrompt(session, listOf(ContentBlock.Text("Read the file")))

                    assertTrue(
                        ops.permissionRequests.isEmpty(),
                        "the deny policy must refuse without asking the user",
                    )
                    val followUp = llmMock.requestBodies.getOrNull(1)
                    assertTrue(
                        followUp != null && followUp.contains("permissions.readableDirectories"),
                        "the model must be told which config entry would have covered the path",
                    )
                    assertTrue(
                        followUp.contains(target.absolutePath) || followUp.contains(target.name),
                        "the refusal must name the refused target",
                    )
                    assertTrue(
                        !target.readText().isEmpty(),
                        "the fixture must be untouched (the tool never ran)",
                    )
                    println("[ok] unlistedPaths=deny refused without a prompt and named the config entry")
                } finally {
                    connection.close()
                }
            }
        } finally {
            unlistedDir.deleteRecursively()
        }
    }
}