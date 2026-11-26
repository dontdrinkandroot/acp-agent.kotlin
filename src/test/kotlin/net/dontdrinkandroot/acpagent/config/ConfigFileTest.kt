package net.dontdrinkandroot.acpagent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigFileTest {

    @Test
    fun `an empty object parses to an all-unset config file`() {
        assertEquals(ConfigFile(), ConfigFile.parse("{}"))
    }

    @Test
    fun `the documented example parses into the typed model`() {
        val file = ConfigFile.parse(
            """
            {
              "openRouter": {
                "apiKeyFile": "/home/me/.config/ddr-acp-agent/openrouter.key",
                "defaultModel": "openrouter/auto",
                "baseUrl": "https://openrouter.ai/api/v1",
                "autoThroughputSorting": true
              },
              "tools": {
                "maxTurnRequests": 100,
                "fsProxy": true,
                "bash": {
                  "timeoutSeconds": 600
                },
                "webFetch": {
                  "allowPrivate": false
                }
              },
              "mcp": {
                "trustAnnotations": true
              },
              "permissions": {
                "readableDirectories": ["/srv/git"],
                "writableDirectories": ["/srv/git/github.com/me/scratch"],
                "unlistedPaths": "prompt"
              },
              "scratchDir": "/tmp/ddr-acp-agent"
            }
            """.trimIndent()
        )
        assertEquals(
            ConfigFile(
                openRouter = ConfigFile.OpenRouter(
                    apiKeyFile = "/home/me/.config/ddr-acp-agent/openrouter.key",
                    defaultModel = "openrouter/auto",
                    baseUrl = "https://openrouter.ai/api/v1",
                    autoThroughputSorting = true,
                ),
                tools = ConfigFile.Tools(
                    maxTurnRequests = 100,
                    fsProxy = true,
                    bash = ConfigFile.Tools.Bash(timeoutSeconds = 600),
                    webFetch = ConfigFile.Tools.WebFetch(allowPrivate = false),
                ),
                mcp = ConfigFile.Mcp(trustAnnotations = true),
                permissions = ConfigFile.Permissions(
                    readableDirectories = listOf("/srv/git"),
                    writableDirectories = listOf("/srv/git/github.com/me/scratch"),
                    unlistedPaths = UnlistedPaths.PROMPT,
                ),
                scratchDir = "/tmp/ddr-acp-agent",
            ),
            file,
        )
    }

    @Test
    fun `unknown keys are ignored at every level`() {
        assertEquals(
            ConfigFile(mcp = ConfigFile.Mcp(trustAnnotations = false)),
            ConfigFile.parse(
                """{"futureTopLevel": 1, "mcp": {"trustAnnotations": false, "futureOption": "x"}}"""
            ),
        )
    }

    @Test
    fun `wrong types name the JSON path`() {
        assertEquals(
            "tools.bash.timeoutSeconds must be a number",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"tools": {"bash": {"timeoutSeconds": "600"}}}""")
            }.message,
        )
        assertEquals(
            "tools.fsProxy must be a boolean",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"tools": {"fsProxy": "yes"}}""")
            }.message,
        )
        assertEquals(
            "permissions.readableDirectories[1] must be a string",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"permissions": {"readableDirectories": ["/srv", 3]}}""")
            }.message,
        )
        assertEquals(
            "openRouter must be an object",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"openRouter": []}""")
            }.message,
        )
        assertEquals(
            "not valid JSON",
            assertFailsWith<IllegalArgumentException> { ConfigFile.parse("{nope") }
                .message!!.substringBefore(":"),
        )
        assertEquals(
            "top level must be a JSON object",
            assertFailsWith<IllegalArgumentException> { ConfigFile.parse("[]") }.message,
        )
    }

    @Test
    fun `invalid values fail with the accepted shape`() {
        assertEquals(
            "tools.bash.timeoutSeconds must be between 1 and 3600, got -5",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"tools": {"bash": {"timeoutSeconds": -5}}}""")
            }.message,
        )
        assertEquals(
            "tools.maxTurnRequests must be at least 1, got 0",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"tools": {"maxTurnRequests": 0}}""")
            }.message,
        )
        assertEquals(
            "permissions.unlistedPaths must be 'prompt' or 'deny', got 'maybe'",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"permissions": {"unlistedPaths": "maybe"}}""")
            }.message,
        )
        assertEquals(
            "permissions.readableDirectories entries must be absolute paths, got 'srv/git'",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"permissions": {"readableDirectories": ["srv/git"]}}""")
            }.message,
        )
        assertEquals(
            "scratchDir must be an absolute path, got 'scratch'",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"scratchDir": "scratch"}""")
            }.message,
        )
        assertEquals(
            "scratchDir must not be the filesystem root",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"scratchDir": "/tmp/.."}""")
            }.message,
        )
    }

    @Test
    fun `path lists are deduped and scratchDir is normalized`() {
        val file = ConfigFile.parse(
            """{"permissions": {"readableDirectories": ["/srv", "/srv", "/mnt"]}, "scratchDir": "/tmp/x/../y/"}"""
        )
        assertEquals(listOf("/srv", "/mnt"), file.permissions?.readableDirectories)
        assertEquals("/tmp/y", file.scratchDir)
    }

    @Test
    fun `permission path entries are slash-normalized so spelling variants collapse`() {
        // Mount paths are slash-normalized like sandbox.stateDir: "/srv/" and "/srv" name the
        // same mount, and a duplicate --mount would be a launcher bug (the last one wins).
        val file = ConfigFile.parse(
            """{"permissions": {"readableDirectories": ["/srv/", "/srv", "/mnt//", "/"], "writableDirectories": ["/w/"]}}"""
        )
        assertEquals(listOf("/srv", "/mnt", "/"), file.permissions?.readableDirectories)
        assertEquals(listOf("/w"), file.permissions?.writableDirectories)
    }

    @Test
    fun `unlisted paths parse case-insensitively`() {
        assertEquals(
            UnlistedPaths.DENY,
            ConfigFile.parse("""{"permissions": {"unlistedPaths": "DENY"}}""").permissions?.unlistedPaths,
        )
    }

    @Test
    fun `the sandbox group parses into the typed model`() {
        val file = ConfigFile.parse(
            """
            {
              "sandbox": {
                "network": "development",
                "stateDir": "/srv/state/ddr-acp-agent",
                "mountCaches": false,
                "homeVolume": "acp-agent-home",
                "extraArgs": ["--label", "probe=1"],
                "capAdd": ["SYS_PTRACE"],
                "rwRootfs": true
              }
            }
            """.trimIndent()
        )
        assertEquals(
            ConfigFile.Sandbox(
                network = "development",
                stateDir = "/srv/state/ddr-acp-agent",
                mountCaches = false,
                homeVolume = "acp-agent-home",
                extraArgs = listOf("--label", "probe=1"),
                capAdd = listOf("SYS_PTRACE"),
                rwRootfs = true,
            ),
            file.sandbox,
        )
    }

    @Test
    fun `the sandbox group is validated for shape only`() {
        assertEquals(
            "sandbox must be an object",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": []}""")
            }.message,
        )
        assertEquals(
            "sandbox.mountCaches must be a boolean",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"mountCaches": "no"}}""")
            }.message,
        )
        assertEquals(
            "sandbox.stateDir must be an absolute path, got 'state'",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"stateDir": "state"}}""")
            }.message,
        )
        assertEquals(
            "sandbox.stateDir must not be the filesystem root",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"stateDir": "/"}}""")
            }.message,
        )
        assertEquals(
            "sandbox.extraArgs[1] must be a string",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"extraArgs": ["--label", 1]}}""")
            }.message,
        )
        assertEquals(
            "sandbox.capAdd[0] must not be blank",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"capAdd": ["  "]}}""")
            }.message,
        )
        assertEquals(
            "sandbox.extraArgs[1] must not be blank",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"extraArgs": ["--label", " "]}}""")
            }.message,
        )
        assertEquals(
            "sandbox.network must not be blank",
            assertFailsWith<IllegalArgumentException> {
                ConfigFile.parse("""{"sandbox": {"network": " "}}""")
            }.message,
        )
    }

    @Test
    fun `sandbox path values are normalized but never resolved against host facts`() {
        // Trailing slashes are normalized (never rejected); the directory itself is not
        // probed here - a host-fact check would fault the containerized agent, which parses
        // the same mounted file.
        val file = ConfigFile.parse(
            """{"sandbox": {"stateDir": "/srv/state/ddr-acp-agent//", "homeVolume": " vol "}}"""
        )
        assertEquals("/srv/state/ddr-acp-agent", file.sandbox?.stateDir)
        assertEquals("vol", file.sandbox?.homeVolume)
    }
}
