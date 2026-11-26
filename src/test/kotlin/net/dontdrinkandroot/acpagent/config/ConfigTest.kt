package net.dontdrinkandroot.acpagent.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class ConfigTest {

    private val home: Path = Files.createTempDirectory("acp-home")

    /** A `Config.load` call with an isolated HOME and a key file in the config dir. */
    private fun load(
        env: Map<String, String> = emptyMap(),
        configJson: String? = null,
        profile: String? = null,
        warnings: MutableList<String> = mutableListOf(),
    ): Config {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        if (configJson != null) {
            configDir.resolve(profile?.let { profileFileName(it) } ?: Config.CONFIG_FILE_NAME).writeText(configJson)
        }
        writeKeyFile(configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME), "sk-default")
        return Config.load(env = env, home = home, profile = profile, warnings = { warnings += it })
    }

    /** Writes a profile file next to the default config file. */
    private fun writeProfile(profile: String, json: String) {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        configDir.resolve(profileFileName(profile)).writeText(json)
    }

    private fun writeKeyFile(path: Path, content: String) {
        path.writeText(content)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }

    @Test
    fun `a missing key is reported with the tried sources`() {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        val e = assertFailsWith<IllegalArgumentException> { Config.load(env = emptyMap(), home = home) }
        assertEquals(
            "No OpenRouter API key configured. Set openRouter.apiKeyFile in config.json, " +
                    "or create '${configDir.resolve("openrouter.key")}'.",
            e.message,
        )
    }

    @Test
    fun `the api key is read from the default location and trimmed`() {
        assertEquals("sk-default", load().openRouterApiKey.value)
    }

    @Test
    fun `the resolved key file is recorded as a path, never as key material`() {
        val config = load()
        assertEquals(
            home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
                .resolve(Config.DEFAULT_API_KEY_FILE_NAME).toString(),
            config.openRouterApiKeyFile,
        )
        assertFalse(config.openRouterApiKeyFile!!.contains("sk-default"), "only the path may be recorded")
    }

    @Test
    fun `a rendered config carries no key material`() {
        val config = load()
        val rendered = config.toString()

        assertFalse(rendered.contains("sk-default"), "the rendered config must not contain the key: $rendered")
        assertFalse(rendered.contains("sk-"), "the rendered config must not contain any key fragment: $rendered")
        assertTrue(rendered.contains("openRouterApiKey=***"), "the field must stay visible but redacted: $rendered")
        assertTrue(rendered.contains("openRouterModel="), "other fields must still render: $rendered")
        assertTrue(rendered.contains("openRouterBaseUrl="), "other fields must still render: $rendered")
    }

    @Test
    fun `a secret renders redacted and exposes its value explicitly`() {
        val secret = Secret("sk-super-secret")

        assertEquals("***", secret.toString())
        assertEquals("sk-super-secret", secret.value)
        assertFalse("$secret".contains("sk-super-secret"), "interpolation must not leak the key")
        assertEquals(Secret("a"), Secret("a"), "equality follows the wrapped value")
        assertNotEquals(Secret("a"), Secret("b"), "distinct values must not compare equal")
    }

    @Test
    fun `a key file readable by group or others is refused`() {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        val keyFile = configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME)
        keyFile.writeText("sk-leaky")
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"))
        val e = assertFailsWith<IllegalArgumentException> { Config.load(env = emptyMap(), home = home) }
        assertTrue(e.message!!.startsWith("API key file '$keyFile' (default location '$keyFile') must be owner-only"))
    }

    @Test
    fun `the env key file is inert`() {
        val envKey = Files.createTempFile("acp-env-key", ".txt")
        writeKeyFile(envKey, "sk-env")
        // The retired variable must neither supply the key nor fail the startup;
        // the default location in the config directory wins.
        val config = load(env = mapOf("DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE" to envKey.toString()))
        assertEquals("sk-default", config.openRouterApiKey.value)
        assertEquals(
            home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
                .resolve(Config.DEFAULT_API_KEY_FILE_NAME).toString(),
            config.openRouterApiKeyFile,
        )
    }

    @Test
    fun `the file key file wins over the default location`() {
        val fileKey = Files.createTempFile("acp-file-key", ".txt")
        writeKeyFile(fileKey, "sk-file")
        val config = load(configJson = """{"openRouter": {"apiKeyFile": "$fileKey"}}""")
        assertEquals("sk-file", config.openRouterApiKey.value)
    }

    @Test
    fun `defaults apply without a config file`() {
        val config = load()
        assertEquals("openrouter/auto", config.openRouterModel)
        assertEquals("https://openrouter.ai/api/v1", config.openRouterBaseUrl)
        assertTrue(config.autoThroughputSortingEnabled)
        assertTrue(config.fsProxyEnabled)
        assertTrue(config.mcpTrustAnnotations)
        assertEquals(600, config.bashTimeoutSeconds)
        assertEquals(100, config.maxTurnRequests)
        assertFalse(config.webFetchAllowPrivate)
        assertEquals(emptyList(), config.readableDirectories)
        assertEquals(emptyList(), config.writableDirectories)
        assertEquals(UnlistedPaths.PROMPT, config.unlistedPaths)
        assertEquals("/tmp/ddr-acp-agent", config.scratchDir)
    }

    @Test
    fun `the config file overrides the defaults`() {
        val config = load(
            configJson = """
                {
                  "openRouter": {
                    "defaultModel": "m/n",
                    "baseUrl": "http://localhost:9/v1",
                    "autoThroughputSorting": false
                  },
                  "tools": {
                    "maxTurnRequests": 7,
                    "fsProxy": false,
                    "bash": {"timeoutSeconds": 42},
                    "webFetch": {"allowPrivate": true}
                  },
                  "mcp": {"trustAnnotations": false},
                  "permissions": {"unlistedPaths": "deny"},
                  "scratchDir": "/var/scratch"
                }
            """.trimIndent()
        )
        assertEquals("m/n", config.openRouterModel)
        assertEquals("http://localhost:9/v1", config.openRouterBaseUrl)
        assertFalse(config.autoThroughputSortingEnabled)
        assertFalse(config.fsProxyEnabled)
        assertFalse(config.mcpTrustAnnotations)
        assertEquals(42, config.bashTimeoutSeconds)
        assertEquals(7, config.maxTurnRequests)
        assertTrue(config.webFetchAllowPrivate)
        assertEquals(UnlistedPaths.DENY, config.unlistedPaths)
        assertEquals("/var/scratch", config.scratchDir)
    }

    @Test
    fun `a profile selects its own file next to the default one`() {
        writeProfile("work", """{"tools": {"maxTurnRequests": 5}}""")
        // The default file is a sibling, not a merge base: only the selected file applies.
        assertEquals(7, load(configJson = """{"tools": {"maxTurnRequests": 7}}""").maxTurnRequests)
        assertEquals(5, load(profile = "work").maxTurnRequests)
    }

    @Test
    fun `a selected profile must exist and a broken one names the file`() {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        writeKeyFile(configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME), "sk-default")
        assertEquals(
            "config file '${configDir.resolve("work.json")}' is not a regular file",
            assertFailsWith<IllegalArgumentException> {
                Config.load(env = emptyMap(), home = home, profile = "work")
            }.message,
        )

        writeProfile("work", """{"tools": {"maxTurnRequests": 0}}""")
        assertEquals(
            "config file '${configDir.resolve("work.json")}': tools.maxTurnRequests must be at least 1, got 0",
            assertFailsWith<IllegalArgumentException> {
                Config.load(env = emptyMap(), home = home, profile = "work")
            }.message,
        )
    }

    @Test
    fun `the retired agent environment family is inert`() {
        // Issue #84: no DDR_ACP_AGENT_* variable is read - not the current names, not the
        // even older legacy spellings. Nothing is applied and nothing warns.
        val warnings = mutableListOf<String>()
        val config = load(
            env = mapOf(
                "DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL" to "env-model",
                "DDR_ACP_AGENT_OPEN_ROUTER_BASE_URL" to "http://env/v1",
                "DDR_ACP_AGENT_OPEN_ROUTER_AUTO_THROUGHPUT_SORTING" to "off",
                "DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS" to "9",
                "DDR_ACP_AGENT_TOOLS_FS_PROXY" to "off",
                "DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS" to "99",
                "DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE" to "1",
                "DDR_ACP_AGENT_MCP_TRUST_ANNOTATIONS" to "0",
                "DDR_ACP_AGENT_PERMISSIONS_UNLISTED_PATHS" to "deny",
                "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES" to "/srv/env",
                "DDR_ACP_AGENT_SCRATCH_DIR" to "/env/scratch",
                "DDR_ACP_AGENT_CONFIG_FILE" to "work.json",
                "OPENROUTER_MODEL" to "legacy",
                "FS_PROXY_ENABLED" to "0",
                "ACP_MAX_TURN_REQUESTS" to "5",
            ),
            warnings = warnings,
        )
        assertEquals("openrouter/auto", config.openRouterModel)
        assertEquals("https://openrouter.ai/api/v1", config.openRouterBaseUrl)
        assertTrue(config.autoThroughputSortingEnabled)
        assertTrue(config.fsProxyEnabled)
        assertTrue(config.mcpTrustAnnotations)
        assertEquals(600, config.bashTimeoutSeconds)
        assertEquals(100, config.maxTurnRequests)
        assertFalse(config.webFetchAllowPrivate)
        assertEquals(UnlistedPaths.PROMPT, config.unlistedPaths)
        assertEquals(emptyList(), config.readableDirectories)
        assertEquals("/tmp/ddr-acp-agent", config.scratchDir)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `path lists and scratch directory come from the file`() {
        val config = load(
            configJson = """
                {
                  "permissions": {"readableDirectories": ["/srv/data"], "writableDirectories": ["/srv/w"]},
                  "scratchDir": "/var/scratch"
                }
            """.trimIndent()
        )
        assertEquals(listOf("/srv/data"), config.readableDirectories)
        assertEquals(listOf("/srv/w"), config.writableDirectories)
        assertEquals("/var/scratch", config.scratchDir)
    }

    @Test
    fun `configured directories that do not exist warn but do not fail`() {
        val warnings = mutableListOf<String>()
        val config = load(
            configJson = """
                {
                  "permissions": {
                    "readableDirectories": ["/does/not/exist"],
                    "writableDirectories": ["$home"]
                  }
                }
            """.trimIndent(),
            warnings = warnings,
        )
        assertEquals(listOf("/does/not/exist"), config.readableDirectories)
        assertEquals(listOf("configured directory '/does/not/exist' does not exist"), warnings)
    }

    @Test
    fun `sandbox keys parse but cannot fault the agent`() {
        // The host config.json is mounted read-only into the sandbox, so its `sandbox`
        // group must be parseable inside the container and must never require a host fact
        // (the stateDir below does not exist anywhere): the agent simply has no sandbox
        // field and starts on its defaults.
        val config = load(
            configJson = """
                {
                  "sandbox": {
                    "network": "development",
                    "stateDir": "/nonexistent/state",
                    "mountCaches": false,
                    "capAdd": ["SYS_PTRACE"]
                  }
                }
            """.trimIndent()
        )
        assertEquals("openrouter/auto", config.openRouterModel)
    }

    @Test
    fun `a relative apiKeyFile in the config file is rejected`() {
        assertEquals(
            "config file openRouter.apiKeyFile must be an absolute path, got 'openrouter.key'",
            assertFailsWith<IllegalArgumentException> {
                load(configJson = """{"openRouter": {"apiKeyFile": "openrouter.key"}}""")
            }.message,
        )
    }

    @Test
    fun `XDG_CONFIG_HOME relocates the config directory`() {
        val xdg = Files.createTempDirectory("acp-xdg")
        val configDir = xdg.resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        writeKeyFile(configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME), "sk-xdg")
        assertEquals(
            "sk-xdg",
            Config.load(env = mapOf("XDG_CONFIG_HOME" to xdg.toString()), home = home).openRouterApiKey.value,
        )
    }
}
