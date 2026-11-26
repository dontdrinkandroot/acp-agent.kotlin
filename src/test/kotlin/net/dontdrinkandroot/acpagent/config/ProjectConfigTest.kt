package net.dontdrinkandroot.acpagent.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * The project permission overlay (issue #85): `<cwd>/.ai/ddr-acp-agent.json` carries only a
 * `permissions` group and is strict at every level - unlike the global file, an unknown key
 * fails loudly, because this file's only job is to widen (or narrow) one trust domain and a
 * silently ignored key would look like a grant that is not there.
 */
class ProjectConfigTest {

    private val cwd: Path = Files.createTempDirectory("acp-project-cwd")

    private fun projectFile(): Path = cwd.resolve(PROJECT_CONFIG_DIR).resolve(PROJECT_CONFIG_FILE)

    private fun write(json: String): Path {
        cwd.resolve(PROJECT_CONFIG_DIR).createDirectories()
        return projectFile().apply { writeText(json) }
    }

    /** Asserts the read fails, names the file, and returns the message after the prefix. */
    private fun refusal(json: String): String {
        write(json)
        val message = assertFailsWith<IllegalArgumentException> { readProjectConfig(cwd) }.message!!
        val prefix = "project file '${projectFile()}': "
        assertTrue(message.startsWith(prefix), "the failure must name the file, was: $message")
        return message.removePrefix(prefix)
    }

    @Test
    fun `a missing project file means no change`() {
        assertNull(readProjectConfig(cwd), "a project without an overlay must behave exactly as before")
    }

    @Test
    fun `an empty overlay is legal and changes nothing`() {
        assertEquals(ConfigFile.Permissions(), parseProjectPermissions("{}"))
        assertEquals(ConfigFile.Permissions(), parseProjectPermissions("""{"permissions": {}}"""))
        assertEquals(
            ConfigFile.Permissions(),
            parseProjectPermissions("""{"permissions": null}"""),
            "an explicit null group is 'not configured', like the global parser",
        )
    }

    @Test
    fun `an unknown top-level key is refused`() {
        assertEquals(
            "unknown key 'permission': a project file may only carry a permissions group",
            refusal("""{"permission": {}}"""),
        )
    }

    @Test
    fun `a known global group is refused with a pointer to config json`() {
        val message = refusal("""{"tools": {"maxTurnRequests": 3}}""")
        assertTrue(message.contains("'tools' cannot be set in a project file"), message)
        assertTrue(
            message.contains("only permissions.* is project-overridable"),
            "the message must state what this file may carry: $message",
        )
        assertTrue(message.contains("config.json"), "the message must name the place the key belongs: $message")
    }

    @Test
    fun `every global-only root key is refused`() {
        // Including `permissions`' siblings the agent reads honestly: the scope is the three
        // permission keys, not "whatever the global file carries".
        listOf("openRouter", "tools", "mcp", "scratchDir", "sandbox").forEach { key ->
            assertTrue(
                refusal("""{"$key": {}}""").contains("'$key' cannot be set in a project file"),
                "expected '$key' to be refused with the config.json pointer",
            )
        }
    }

    @Test
    fun `an unknown key inside permissions is refused`() {
        assertEquals(
            "unknown key 'permissions.readableDirectorie': only readableDirectories, writableDirectories, " +
                    "unlistedPaths can be set in a project file",
            refusal("""{"permissions": {"readableDirectorie": ["/srv"]}}"""),
        )
    }

    @Test
    fun `permissions must be an object`() {
        assertEquals("permissions must be an object", refusal("""{"permissions": []}"""))
    }

    @Test
    fun `the top level must be an object`() {
        assertEquals("top level must be a JSON object", refusal("""[]"""))
    }

    @Test
    fun `invalid json is refused`() {
        assertTrue(refusal("{oops").startsWith("not valid JSON"), "expected a parse failure naming the file")
    }

    @Test
    fun `a relative path is refused`() {
        assertEquals(
            "permissions.readableDirectories entries must be absolute paths, got 'srv/git'",
            refusal("""{"permissions": {"readableDirectories": ["srv/git"]}}"""),
        )
    }

    @Test
    fun `the filesystem root is refused`() {
        assertEquals(
            "permissions.writableDirectories entries must not be the filesystem root",
            refusal("""{"permissions": {"writableDirectories": ["/"]}}"""),
        )
    }

    @Test
    fun `a named directory must exist`() {
        val missing = cwd.resolve("missing-tier")
        assertEquals(
            "permissions.readableDirectories: '$missing' does not exist or is not a directory",
            refusal("""{"permissions": {"readableDirectories": ["$missing"]}}"""),
            "a checked-in overlay naming a missing directory is a broken file, not a machine-dependent mount",
        )
    }

    @Test
    fun `a named path that is a file is refused`() {
        val file = cwd.resolve("not-a-tier").apply { writeText("x") }
        assertEquals(
            "permissions.writableDirectories: '$file' does not exist or is not a directory",
            refusal("""{"permissions": {"writableDirectories": ["$file"]}}"""),
        )
    }

    @Test
    fun `duplicates and trailing slashes collapse`() {
        val directory = Files.createTempDirectory("acp-project-tier").toString()
        val permissions = parseProjectPermissions(
            """{"permissions": {"readableDirectories": ["$directory/", "$directory", "$directory//"]}}"""
        )
        assertEquals(listOf(directory), permissions.readableDirectories)
    }

    @Test
    fun `unlisted paths parse case-insensitively`() {
        assertEquals(
            UnlistedPaths.DENY,
            parseProjectPermissions("""{"permissions": {"unlistedPaths": "DENY"}}""").unlistedPaths,
        )
    }

    @Test
    fun `the source names the file it was read from`() {
        val file = write("""{"permissions": {"unlistedPaths": "deny"}}""")
        val source = readProjectConfig(cwd)
        assertEquals(file, source?.file)
        assertEquals(UnlistedPaths.DENY, source?.permissions?.unlistedPaths)
    }
}
