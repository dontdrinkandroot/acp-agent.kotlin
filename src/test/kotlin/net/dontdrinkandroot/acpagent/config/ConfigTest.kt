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
        warnings: MutableList<String> = mutableListOf(),
    ): Config {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        if (configJson != null) configDir.resolve(Config.CONFIG_FILE_NAME).writeText(configJson)
        writeKeyFile(configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME), "sk-default")
        return Config.load(env = env, home = home, warnings = { warnings += it })
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
            "No OpenRouter API key configured. Set DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE, " +
                    "openRouter.apiKeyFile in config.json, or create '${configDir.resolve("openrouter.key")}'.",
            e.message,
        )
    }

    @Test
    fun `the api key is read from the default location and trimmed`() {
        assertEquals("sk-default", load().openRouterApiKey)
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
    fun `the env key file wins over the file key file`() {
        val envKey = Files.createTempFile("acp-env-key", ".txt")
        writeKeyFile(envKey, "sk-env")
        val config = load(
            env = mapOf("DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE" to envKey.toString()),
            configJson = """{"openRouter": {"apiKeyFile": "/does/not/matter"}}""",
        )
        assertEquals("sk-env", config.openRouterApiKey)
    }

    @Test
    fun `the file key file wins over the default location`() {
        val fileKey = Files.createTempFile("acp-file-key", ".txt")
        writeKeyFile(fileKey, "sk-file")
        val config = load(configJson = """{"openRouter": {"apiKeyFile": "$fileKey"}}""")
        assertEquals("sk-file", config.openRouterApiKey)
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
    fun `the env layer wins over the config file`() {
        val config = load(
            configJson = """
                {
                  "openRouter": {"defaultModel": "file-model", "baseUrl": "http://file/v1", "autoThroughputSorting": false},
                  "tools": {"maxTurnRequests": 7, "fsProxy": false, "bash": {"timeoutSeconds": 42}},
                  "mcp": {"trustAnnotations": false},
                  "permissions": {"unlistedPaths": "deny"},
                  "scratchDir": "/var/scratch"
                }
            """.trimIndent(),
            env = mapOf(
                "DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL" to "env-model",
                "DDR_ACP_AGENT_OPEN_ROUTER_BASE_URL" to "http://env/v1",
                "DDR_ACP_AGENT_OPEN_ROUTER_AUTO_THROUGHPUT_SORTING" to "yes",
                "DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS" to "9",
                "DDR_ACP_AGENT_TOOLS_FS_PROXY" to "on",
                "DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS" to "99",
                "DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE" to "true",
                "DDR_ACP_AGENT_MCP_TRUST_ANNOTATIONS" to "1",
                "DDR_ACP_AGENT_PERMISSIONS_UNLISTED_PATHS" to "prompt",
                "DDR_ACP_AGENT_SCRATCH_DIR" to "/env/scratch",
            ),
        )
        assertEquals("env-model", config.openRouterModel)
        assertEquals("http://env/v1", config.openRouterBaseUrl)
        assertTrue(config.autoThroughputSortingEnabled)
        assertEquals(9, config.maxTurnRequests)
        assertTrue(config.fsProxyEnabled)
        assertEquals(99, config.bashTimeoutSeconds)
        assertTrue(config.webFetchAllowPrivate)
        assertTrue(config.mcpTrustAnnotations)
        assertEquals(UnlistedPaths.PROMPT, config.unlistedPaths)
        assertEquals("/env/scratch", config.scratchDir)
    }

    @Test
    fun `env names are derived from the config paths`() {
        assertEquals("DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE", EnvValues.name("openRouter.apiKeyFile"))
        assertEquals("DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS", EnvValues.name("tools.bash.timeoutSeconds"))
        assertEquals("DDR_ACP_AGENT_TOOLS_FS_PROXY", EnvValues.name("tools.fsProxy"))
        assertEquals("DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE", EnvValues.name("tools.webFetch.allowPrivate"))
        assertEquals("DDR_ACP_AGENT_MCP_TRUST_ANNOTATIONS", EnvValues.name("mcp.trustAnnotations"))
        assertEquals("DDR_ACP_AGENT_SCRATCH_DIR", EnvValues.name("scratchDir"))
    }

    @Test
    fun `legacy env names are not read`() {
        val config = load(
            env = mapOf(
                "OPENROUTER_MODEL" to "legacy",
                "FS_PROXY_ENABLED" to "0",
                "MCP_TRUST_ANNOTATIONS" to "0",
                "ACP_BASH_TIMEOUT_SECONDS" to "5",
                "ACP_MAX_TURN_REQUESTS" to "5",
                "ACP_WEB_FETCH_ALLOW_PRIVATE" to "1",
                "ACP_EXTRA_MOUNTS" to "/srv/legacy",
            )
        )
        assertEquals("openrouter/auto", config.openRouterModel)
        assertTrue(config.fsProxyEnabled)
        assertTrue(config.mcpTrustAnnotations)
        assertEquals(600, config.bashTimeoutSeconds)
        assertEquals(100, config.maxTurnRequests)
        assertFalse(config.webFetchAllowPrivate)
        assertEquals(emptyList(), config.readableDirectories)
    }

    @Test
    fun `strict booleans accept the documented spellings and reject typos`() {
        for (value in listOf("1", "true", "TRUE", "yes", "On")) {
            assertTrue(
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_FS_PROXY" to value)).fsProxyEnabled,
                "'$value' must enable the option",
            )
        }
        for (value in listOf("0", "false", "No", "OFF")) {
            assertFalse(
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_FS_PROXY" to value)).fsProxyEnabled,
                "'$value' must disable the option",
            )
        }
        assertEquals(
            "DDR_ACP_AGENT_TOOLS_FS_PROXY must be one of 1/true/yes/on or 0/false/no/off, got 'garbage'",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_FS_PROXY" to "garbage"))
            }.message,
        )
    }

    @Test
    fun `strict numbers reject out-of-range and non-numeric values`() {
        assertEquals(
            "DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS must be between 1 and 3600, got 3601",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS" to "3601"))
            }.message,
        )
        assertEquals(
            "DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS must be between 1 and 3600, got 0",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS" to "0"))
            }.message,
        )
        assertEquals(
            "DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS must be an integer, got 'garbage'",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS" to "garbage"))
            }.message,
        )
        assertEquals(
            "DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS must be at least 1, got 0",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS" to "0"))
            }.message,
        )
    }

    @Test
    fun `path lists come from the file and the env overrides them comma separated`() {
        val fromFile = load(
            configJson = """{"permissions": {"readableDirectories": ["/srv/data"], "writableDirectories": ["/srv/w"]}}"""
        )
        assertEquals(listOf("/srv/data"), fromFile.readableDirectories)
        assertEquals(listOf("/srv/w"), fromFile.writableDirectories)

        val fromEnv = load(
            configJson = """{"permissions": {"readableDirectories": ["/srv/data"]}}""",
            env = mapOf(
                "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES" to " /mnt/a ,, /mnt/b , /mnt/a ",
                "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES" to "/mnt/w",
            ),
        )
        assertEquals(listOf("/mnt/a", "/mnt/b"), fromEnv.readableDirectories)
        assertEquals(listOf("/mnt/w"), fromEnv.writableDirectories)
    }

    @Test
    fun `non-absolute path list entries are rejected`() {
        assertEquals(
            "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES entries must be absolute paths, got 'srv/data'",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES" to "srv/data"))
            }.message,
        )
    }

    @Test
    fun `configured directories that do not exist warn but do not fail`() {
        val warnings = mutableListOf<String>()
        val config = load(
            env = mapOf(
                "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES" to "/does/not/exist",
                "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES" to home.toString(),
            ),
            warnings = warnings,
        )
        assertEquals(listOf("/does/not/exist"), config.readableDirectories)
        assertEquals(listOf("configured directory '/does/not/exist' does not exist"), warnings)
    }

    @Test
    fun `scratch directory keeps its env name and is validated`() {
        assertEquals("/var/scratch", load(env = mapOf("DDR_ACP_AGENT_SCRATCH_DIR" to "/var/scratch/")).scratchDir)
        assertEquals("/tmp/y", load(env = mapOf("DDR_ACP_AGENT_SCRATCH_DIR" to "/tmp/x/../y")).scratchDir)
        assertEquals(
            "DDR_ACP_AGENT_SCRATCH_DIR must be an absolute path, got 'scratch'",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_SCRATCH_DIR" to "scratch"))
            }.message,
        )
        for (root in listOf("/", "/tmp/..", "//")) {
            assertFailsWith<IllegalArgumentException>("expected '$root' to be refused") {
                load(env = mapOf("DDR_ACP_AGENT_SCRATCH_DIR" to root))
            }
        }
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
    fun `an explicit config file name must not be a path`() {
        assertEquals(
            "DDR_ACP_AGENT_CONFIG_FILE must be a file name inside the config directory, not a path, " +
                    "got '/etc/config.json'",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to "/etc/config.json"))
            }.message,
        )
        for (name in listOf(".", "..")) {
            assertFailsWith<IllegalArgumentException>("expected '$name' to be refused") {
                load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to name))
            }
        }
    }

    @Test
    fun `an explicitly selected config file must exist in the config directory`() {
        assertEquals(
            "config file '${home.resolve(".config").resolve(Config.APP_CONFIG_DIR).resolve("work.json")}' " +
                    "is not a regular file",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to "work.json"))
            }.message,
        )
    }

    @Test
    fun `a blank config file selection falls back to the default name`() {
        // The env layer trims everywhere else: a blank value must not count as
        // an "explicit selection" (a missing profile is fatal, the default is not).
        assertEquals("sk-default", load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to "  ")).openRouterApiKey)
    }

    @Test
    fun `an explicit config file name selects a profile and a broken one names the file`() {
        val configDir = home.resolve(".config").resolve(Config.APP_CONFIG_DIR)
        configDir.createDirectories()
        val profile = configDir.resolve("work.json")
        profile.writeText("""{"tools": {"maxTurnRequests": 5}}""")
        assertEquals(
            5,
            load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to "work.json")).maxTurnRequests,
        )

        profile.writeText("""{"tools": {"maxTurnRequests": 0}}""")
        assertEquals(
            "config file '$profile': tools.maxTurnRequests must be at least 1, got 0",
            assertFailsWith<IllegalArgumentException> {
                load(env = mapOf("DDR_ACP_AGENT_CONFIG_FILE" to "work.json"))
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
            Config.load(env = mapOf("XDG_CONFIG_HOME" to xdg.toString()), home = home).openRouterApiKey,
        )
    }
}
