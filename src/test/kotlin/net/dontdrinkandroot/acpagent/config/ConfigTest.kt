package net.dontdrinkandroot.acpagent.config

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigParseTest {

    @Test
    fun `fromEnv requires api key`() {
        val e = assertFailsWith<IllegalArgumentException> { Config.fromEnv(emptyMap()) }
        assertEquals("OPENROUTER_API_KEY is not set", e.message)
        assertEquals("sk-x", Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "sk-x")).openRouterApiKey)
    }

    @Test
    fun `api key file is read and trimmed`() {
        val keyFile = File.createTempFile("acp-key", ".txt").apply {
            writeText("  sk-file-key\n")
            deleteOnExit()
        }
        assertEquals(
            "sk-file-key",
            Config.fromEnv(mapOf("OPENROUTER_API_KEY_FILE" to keyFile.absolutePath)).openRouterApiKey,
        )
    }

    @Test
    fun `setting both api key and api key file is an error`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "sk-x", "OPENROUTER_API_KEY_FILE" to "/tmp/key"))
        }
        assertEquals("Set either OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE, not both", e.message)
    }

    @Test
    fun `api key file problems fail loudly with the path`() {
        assertEquals(
            "OPENROUTER_API_KEY_FILE /does/not/exist is not a readable file",
            assertFailsWith<IllegalArgumentException> {
                Config.fromEnv(mapOf("OPENROUTER_API_KEY_FILE" to "/does/not/exist"))
            }.message,
        )
        val dir = Files.createTempDirectory("acp-keydir").toFile()
        assertEquals(
            "OPENROUTER_API_KEY_FILE ${dir.absolutePath} is not a readable file",
            assertFailsWith<IllegalArgumentException> {
                Config.fromEnv(mapOf("OPENROUTER_API_KEY_FILE" to dir.absolutePath))
            }.message,
        )
        val empty = File.createTempFile("acp-key-empty", ".txt").apply { deleteOnExit() }
        assertEquals(
            "OPENROUTER_API_KEY_FILE ${empty.absolutePath} is empty",
            assertFailsWith<IllegalArgumentException> {
                Config.fromEnv(mapOf("OPENROUTER_API_KEY_FILE" to empty.absolutePath))
            }.message,
        )
    }

    @Test
    fun `fromEnv applies defaults for model and baseUrl`() {
        val config = Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "sk-x"))
        assertEquals("openrouter/auto", config.openRouterModel)
        assertEquals("https://openrouter.ai/api/v1", config.openRouterBaseUrl)
        assertTrue(config.autoThroughputSortingEnabled, "auto throughput sorting must default to enabled")
        assertTrue(config.fsProxyEnabled, "fs proxy must default to enabled")
    }

    @Test
    fun `auto throughput sorting parsing honours the zero toggle`() {
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "1"))
                .autoThroughputSortingEnabled,
        )
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).autoThroughputSortingEnabled,
        )
        assertTrue(
            Config.fromEnv(
                mapOf(
                    "OPENROUTER_API_KEY" to "k",
                    "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "garbage"
                )
            )
                .autoThroughputSortingEnabled,
        )
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "0"))
                .autoThroughputSortingEnabled,
        )
    }

    @Test
    fun `fromEnv overrides model and baseUrl`() {
        val config = Config.fromEnv(
            mapOf(
                "OPENROUTER_API_KEY" to "sk-x",
                "OPENROUTER_MODEL" to "m/n",
                "OPENROUTER_BASE_URL" to "http://localhost:9/v1",
            )
        )
        assertEquals("m/n", config.openRouterModel)
        assertEquals("http://localhost:9/v1", config.openRouterBaseUrl)
    }

    @Test
    fun `fs proxy parsing honours the zero toggle`() {
        assertTrue(Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).fsProxyEnabled)
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "FS_PROXY_ENABLED" to "garbage"))
                .fsProxyEnabled,
        )
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "FS_PROXY_ENABLED" to "0"))
                .fsProxyEnabled,
        )
    }

    @Test
    fun `mcp trust annotations parsing honours the zero toggle`() {
        assertTrue(Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).mcpTrustAnnotations)
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "MCP_TRUST_ANNOTATIONS" to "garbage"))
                .mcpTrustAnnotations,
        )
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "MCP_TRUST_ANNOTATIONS" to "0"))
                .mcpTrustAnnotations,
        )
    }

    @Test
    fun `bash timeout defaults to 600 seconds`() {
        assertEquals(
            600,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).bashTimeoutSeconds,
        )
        assertEquals(
            42,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_BASH_TIMEOUT_SECONDS" to "42"))
                .bashTimeoutSeconds,
        )
    }

    @Test
    fun `bash timeout clamps to at least one second`() {
        assertEquals(
            1,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_BASH_TIMEOUT_SECONDS" to "0"))
                .bashTimeoutSeconds,
        )
        assertEquals(
            1,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_BASH_TIMEOUT_SECONDS" to "-5"))
                .bashTimeoutSeconds,
        )
        assertEquals(
            600,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_BASH_TIMEOUT_SECONDS" to "garbage"))
                .bashTimeoutSeconds,
        )
    }

    @Test
    fun `max turn requests defaults to 100`() {
        assertEquals(
            100,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).maxTurnRequests,
        )
        assertEquals(
            7,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_MAX_TURN_REQUESTS" to "7"))
                .maxTurnRequests,
        )
    }

    @Test
    fun `max turn requests clamps to at least one`() {
        assertEquals(
            1,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_MAX_TURN_REQUESTS" to "0"))
                .maxTurnRequests,
        )
        assertEquals(
            1,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_MAX_TURN_REQUESTS" to "-3"))
                .maxTurnRequests,
        )
        assertEquals(
            100,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_MAX_TURN_REQUESTS" to "garbage"))
                .maxTurnRequests,
        )
    }

    @Test
    fun `web fetch private hosts default to blocked`() {
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).webFetchAllowPrivate,
        )
        assertEquals(
            true,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_WEB_FETCH_ALLOW_PRIVATE" to "1"))
                .webFetchAllowPrivate,
        )
        // Anything but the exact "1" keeps the safe default.
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_WEB_FETCH_ALLOW_PRIVATE" to "true"))
                .webFetchAllowPrivate,
        )
    }

    @Test
    fun `extra mounts default to empty and parse a comma separated list of absolute paths`() {
        assertEquals(
            emptyList(),
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).extraMounts,
        )
        assertEquals(
            listOf("/srv/data", "/mnt/scratch"),
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_EXTRA_MOUNTS" to "/srv/data,/mnt/scratch"))
                .extraMounts,
        )
    }

    @Test
    fun `extra mounts trim entries, drop blanks and dedupe`() {
        assertEquals(
            listOf("/srv/data", "/mnt/scratch"),
            Config.fromEnv(
                mapOf(
                    "OPENROUTER_API_KEY" to "k",
                    "ACP_EXTRA_MOUNTS" to " /srv/data ,, /srv/data, /mnt/scratch ,",
                )
            ).extraMounts,
        )
    }

    @Test
    fun `extra mounts ignore relative entries fail-open`() {
        assertEquals(
            listOf("/srv/data"),
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "ACP_EXTRA_MOUNTS" to "srv/data,/srv/data"))
                .extraMounts,
        )
    }
}
