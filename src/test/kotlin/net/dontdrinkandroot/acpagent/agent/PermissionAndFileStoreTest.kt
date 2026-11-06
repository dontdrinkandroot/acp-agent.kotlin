package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.config.UnlistedPaths
import net.dontdrinkandroot.acpagent.tools.*
import java.nio.file.Files
import kotlin.test.*

class PermissionAndFileStoreTest {

    private fun pathArgs(path: String): JsonObject = buildJsonObject { put("path", path) }

    /**
     * Adapter over [permissionDecision] for the pre-#50 assertions: under the
     * default policy (`unlistedPaths = prompt`) "needs a prompt" is exactly
     * "not allowed outright". The tiers themselves (writable, deny) are pinned
     * by their own tests below.
     */
    private fun permissionNeeded(
        cwd: String,
        tool: AgentTool,
        arguments: JsonObject,
        trustedReadPaths: List<String> = emptyList(),
    ): Boolean = permissionDecision(
        cwd,
        tool,
        arguments,
        PermissionPolicy(readableDirectories = trustedReadPaths),
    ) != PermissionDecision.ALLOW

    @Test
    fun `in project read needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "x")
        assertEquals(false, permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(file.toString())))
    }

    @Test
    fun `out of project read needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempFile("acp-outside", ".txt")
        assertEquals(true, permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(outside.toString())))
    }

    @Test
    fun `out of project read inside a trusted read path needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        val file = trusted.resolve("notes.txt")
        Files.writeString(file, "x")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(file.toString()), listOf(trusted.toString())),
        )
    }

    @Test
    fun `out of project write inside a trusted read path still needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                WriteFileTool(),
                pathArgs(trusted.resolve("x.txt").toString()),
                listOf(trusted.toString()),
            ),
        )
    }

    @Test
    fun `out of project read outside every trusted read path still needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        val untrusted = Files.createTempDirectory("acp-untrusted")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                ReadFileTool(),
                pathArgs(untrusted.resolve("x.txt").toString()),
                listOf(trusted.toString()),
            ),
        )
    }

    @Test
    fun `trusted read path carve-out covers all read-only path tools`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        val trustedPaths = listOf(trusted.toString())
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                ListDirTool(),
                pathArgs(trusted.resolve("sub").toString()),
                trustedPaths,
            ),
        )
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GlobTool(),
                buildJsonObject { put("pattern", "*.txt"); put("path", trusted.resolve("sub").toString()) },
                trustedPaths,
            ),
        )
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("pattern", "x"); put("path", trusted.resolve("sub").toString()) },
                trustedPaths,
            ),
        )
    }

    @Test
    fun `relative path resolving into the project stays prompt-free even when a trusted root exists`() {
        val dir = Files.createTempDirectory("acp-perm")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "x")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), ReadFileTool(), pathArgs("a.txt"), listOf("/nonexistent-trusted")),
        )
    }

    @Test
    fun `scratch directory is a read-trust root in every mode and writes still prompt`() {
        val dir = Files.createTempDirectory("acp-perm")
        val scratch = prepareScratchDirectory(Files.createTempDirectory("acp-scratch").resolve("agent").toString())
        val file = java.nio.file.Path.of(scratch).resolve("notes.txt")
        Files.writeString(file, "scratch")
        val trustedPaths = listOf(scratch)
        // Issue #10: reads/searches are prompt-free there, writes are not.
        assertEquals(false, permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(file.toString()), trustedPaths))
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                ListDirTool(),
                pathArgs(file.parent.toString()),
                trustedPaths,
            ),
        )
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("pattern", "scratch"); put("path", file.toString()) },
                trustedPaths,
            ),
        )
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GlobTool(),
                buildJsonObject { put("pattern", "*.txt"); put("path", file.parent.toString()) },
                trustedPaths,
            ),
        )
        assertEquals(true, permissionNeeded(dir.toString(), WriteFileTool(), pathArgs(file.toString()), trustedPaths))
        // The trust is confined to the directory: a sibling of it is not covered.
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                ReadFileTool(),
                pathArgs(java.nio.file.Path.of(scratch).resolveSibling("elsewhere.txt").toString()),
                trustedPaths,
            ),
        )
    }

    @Test
    fun `symlink inside the session cwd pointing into a trusted read path is prompt-free`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        val target = trusted.resolve("notes.txt")
        Files.writeString(target, "x")
        val link = dir.resolve("link")
        Files.createSymbolicLink(link, target)
        assertEquals(
            false,
            permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(link.toString()), listOf(trusted.toString())),
        )
    }

    @Test
    fun `symlink inside a trusted read path escaping it still needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val trusted = Files.createTempDirectory("acp-trusted")
        val outside = Files.createTempFile("acp-outside", ".txt")
        val link = trusted.resolve("escape")
        Files.createSymbolicLink(link, outside)
        assertEquals(
            true,
            permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(link.toString()), listOf(trusted.toString())),
        )
    }

    @Test
    fun `symlinked search root escaping the project still needs permission`() {
        // The search tools deliberately follow a symlinked root named by the
        // model; the permission layer is what decides whether the resolved
        // target is approved, so an escaping root must prompt.
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val link = dir.resolve("outside-link")
        Files.createSymbolicLink(link, outside)
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                GlobTool(),
                buildJsonObject { put("pattern", "**/*"); put("path", link.toString()) }
            )
        )
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("pattern", "x"); put("path", link.toString()) }
            )
        )
    }

    @Test
    fun `symlinked search root inside the project stays prompt-free`() {
        val dir = Files.createTempDirectory("acp-perm")
        val target = dir.resolve("sub")
        Files.createDirectories(target)
        val link = dir.resolve("sub-link")
        Files.createSymbolicLink(link, target)
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GlobTool(),
                buildJsonObject { put("pattern", "**/*"); put("path", link.toString()) }
            )
        )
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("pattern", "x"); put("path", link.toString()) }
            )
        )
    }

    @Test
    fun `in project write needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), WriteFileTool(), pathArgs(dir.resolve("x.txt").toString()))
        )
    }

    @Test
    fun `out of project write needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(dir.toString(), WriteFileTool(), pathArgs(outside.resolve("x.txt").toString()))
        )
    }

    @Test
    fun `grep with out of project root needs permission and default root does not`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("path", outside.toString()); put("pattern", "x") }
            )
        )
        assertEquals(
            false,
            permissionNeeded(dir.toString(), GrepTool(), buildJsonObject { put("pattern", "x") })
        )
    }

    @Test
    fun `non path non mutating tool never needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), UpdatePlanTool(), buildJsonObject { put("entries", JsonPrimitive("[]")) })
        )
    }

    private fun moveArgs(source: String, destination: String): JsonObject = buildJsonObject {
        put("source", source)
        put("destination", destination)
    }

    @Test
    fun `move within the project needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val source = dir.resolve("a.txt")
        Files.writeString(source, "x")
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(source.toString(), dir.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `move with an out of project destination needs permission even when the source is inside`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val source = dir.resolve("a.txt")
        Files.writeString(source, "x")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(source.toString(), outside.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `move with an out of project source needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(outside.resolve("a.txt").toString(), dir.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `delete within the project needs no permission and outside needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), DeleteFileTool(), pathArgs(dir.resolve("a.txt").toString()))
        )
        assertEquals(
            true,
            permissionNeeded(dir.toString(), DeleteFileTool(), pathArgs(outside.resolve("a.txt").toString()))
        )
    }

    @Test
    fun `selectFileStore uses the client proxy only when enabled and both caps are present`() {
        val client = RecordingClient()
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = true,
            ) is ClientFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = false,
            ) is LocalFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true)),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
    }

    @Test
    fun `selectFileStore falls back to local without a client`() {
        assertTrue(
            selectFileStore(
                null,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
    }

    @Test
    fun `client proxy read of an exact limit window ending in a newline is complete`() = runBlocking {
        // The proxy returns exactly `limit` lines where the last line ends with
        // a newline: that terminator means the window reached EOF, so no
        // phantom footer may be emitted.
        val exactWindow = (1..5).joinToString("") { "line $it\n" }
        val store = ClientFileStore(ConfigurableFsClient(exactWindow))
        val read = store.readFile("/any", 1, 5)
        assertTrue(read.complete, "an exact-limit window ending in a newline has no more lines below")
        assertEquals(exactWindow, read.content)

        // A limit-ignoring client (whole file) delivering one line more than
        // the window is sliced locally: the window keeps its `limit` lines and
        // is truncated because line 6 exists (issue #6 semantics).
        val cutWindow = (1..5).joinToString("") { "line $it\n" } + "line 6"
        val truncated = ClientFileStore(ConfigurableFsClient(cutWindow)).readFile("/any", 1, 5)
        assertFalse(truncated.complete)
        assertEquals(exactWindow, truncated.content, "the over-delivered line must not leak into the window")
    }

    @Test
    fun `client proxy exact-full window is disambiguated by an eof probe`() = runBlocking {
        // Issue #6: an exact-`limit` window's shape cannot say whether more
        // lines follow (a trailing newline is a terminator, not EOF). The
        // store must ask for the line right after the window: one real line
        // below -> truncated, with the probe arguments pinned.
        val client = SlicingFsClient("a\nb\nc\nd\ne")
        val read = ClientFileStore(client).readFile("/any", 1, 2)
        assertEquals("a\nb\n", read.content)
        assertFalse(read.complete, "line 3 exists below the exact-full window")
        assertEquals(
            listOf<Pair<UInt?, UInt?>>(1u to 2u, 3u to 1u),
            client.readCalls,
            "the eof probe must ask for the single line right after the window",
        )
    }

    @Test
    fun `client proxy final window of a file without trailing newline is complete`() = runBlocking {
        // Issue #6's exact scenario: the final window of a file not ending in
        // a newline must be complete (no phantom footer past EOF).
        val client = SlicingFsClient("a\nb\nc")
        val read = ClientFileStore(client).readFile("/any", 3, 1)
        assertEquals("c", read.content)
        assertTrue(read.complete, "line 3 is the last line; nothing follows")
        assertEquals(
            listOf<Pair<UInt?, UInt?>>(3u to 1u, 4u to 1u),
            client.readCalls,
            "the eof probe must ask for the single line right after the window",
        )
    }

    @Test
    fun `a failing eof probe falls back to truncated instead of failing the read`() = runBlocking {
        // Conservative (issue #6): when the probe cannot settle whether more
        // lines follow, the read must still succeed but claim truncated - a
        // phantom footer beats a silently missing one.
        val client = FailingProbeFsClient("a\nb")
        val read = ClientFileStore(client).readFile("/any", 1, 1)
        assertEquals("a\n", read.content)
        assertFalse(read.complete, "an unsettled window must render the continue footer")
    }

    @Test
    fun `client proxy read without a limit is complete`() = runBlocking {
        val store = ClientFileStore(ConfigurableFsClient("anything"))
        val read = store.readFile("/any", null, null)
        assertTrue(read.complete)
        assertNull(read.total)
    }

    @Test
    fun `a writable directory allows writes prompt-free and implies readable`() {
        val dir = Files.createTempDirectory("acp-perm")
        val writable = Files.createTempDirectory("acp-writable")
        val policy = PermissionPolicy(writableDirectories = listOf(writable.toString()))
        assertEquals(
            PermissionDecision.ALLOW,
            permissionDecision(dir.toString(), WriteFileTool(), pathArgs(writable.resolve("x.txt").toString()), policy),
            "a mutating tool under the writable tier runs prompt-free",
        )
        assertEquals(
            PermissionDecision.ALLOW,
            permissionDecision(dir.toString(), ReadFileTool(), pathArgs(writable.resolve("f.txt").toString()), policy),
            "writable implies readable",
        )
    }

    @Test
    fun `a readable directory does not allow writes`() {
        val dir = Files.createTempDirectory("acp-perm")
        val readable = Files.createTempDirectory("acp-readable")
        val policy = PermissionPolicy(readableDirectories = listOf(readable.toString()))
        assertEquals(
            PermissionDecision.PROMPT,
            permissionDecision(dir.toString(), WriteFileTool(), pathArgs(readable.resolve("x.txt").toString()), policy),
        )
    }

    @Test
    fun `a partially covered call is unlisted`() {
        val dir = Files.createTempDirectory("acp-perm")
        val writable = Files.createTempDirectory("acp-writable")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val policy = PermissionPolicy(writableDirectories = listOf(writable.toString()))
        assertEquals(
            PermissionDecision.PROMPT,
            permissionDecision(
                dir.toString(),
                MoveFileTool(),
                moveArgs(writable.resolve("a.txt").toString(), outside.resolve("b.txt").toString()),
                policy,
            ),
            "every target must be covered by the tier",
        )
    }

    @Test
    fun `unlisted paths set to deny refuses outside every tier without a prompt`() {
        val dir = Files.createTempDirectory("acp-perm")
        val readable = Files.createTempDirectory("acp-readable")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val policy = PermissionPolicy(
            readableDirectories = listOf(readable.toString()),
            unlistedPaths = UnlistedPaths.DENY,
        )
        assertEquals(
            PermissionDecision.DENY,
            permissionDecision(dir.toString(), ReadFileTool(), pathArgs(outside.resolve("x").toString()), policy),
        )
        assertEquals(
            PermissionDecision.ALLOW,
            permissionDecision(dir.toString(), ReadFileTool(), pathArgs(readable.resolve("x").toString()), policy),
            "the configured tier still applies",
        )
        assertEquals(
            PermissionDecision.ALLOW,
            permissionDecision(dir.toString(), WriteFileTool(), pathArgs(dir.resolve("x").toString()), policy),
            "the cwd stays implicitly read+write",
        )
    }

    @Test
    fun `non path tools are unaffected by the deny policy`() {
        val dir = Files.createTempDirectory("acp-perm")
        val policy = PermissionPolicy(unlistedPaths = UnlistedPaths.DENY)
        assertEquals(
            PermissionDecision.ALLOW,
            permissionDecision(
                dir.toString(),
                UpdatePlanTool(),
                buildJsonObject { put("entries", JsonPrimitive("[]")) },
                policy
            ),
        )
        assertEquals(
            PermissionDecision.PROMPT,
            permissionDecision(dir.toString(), BashTool(), buildJsonObject { put("command", "ls") }, policy),
            "bash keeps its own always-prompt semantics",
        )
    }

    @Test
    fun `the deny refusal names the tool the targets and the config entry`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val target = outside.resolve("x.txt").toString()
        val refusal = unlistedPathRefusal(ReadFileTool(), pathArgs(target))
        assertTrue(refusal.contains("\"read_file\""), refusal)
        assertTrue(refusal.contains("'$target'"), refusal)
        assertTrue(refusal.contains("permissions.readableDirectories"), refusal)
        assertEquals(
            "permissions.writableDirectories",
            unlistedPathRefusal(WriteFileTool(), pathArgs(target))
                .substringAfter("add the path to ")
                .substringBefore(" "),
        )
    }
}

/**
 * A strict line-slicing fs proxy double whose EOF probe fails: reads starting
 * past line 1 (the probe's signature) throw, so tests can pin the conservative
 * fallback.
 */
private class FailingProbeFsClient(private val content: String) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        if (line != null && line > 1u) throw FileStoreException("client read failed")
        val lines = rawVisualLines(content)
        val to = limit?.let { it.toInt().coerceIn(0, lines.size) } ?: lines.size
        return ReadTextFileResponse(lines.subList(0, to).joinToString(""))
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse =
        WriteTextFileResponse()
}

private class ConfigurableFsClient(private val content: String) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse = ReadTextFileResponse(content)

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse =
        WriteTextFileResponse()
}

/**
 * A strict line-slicing fs proxy double: honors `line`/`limit` like a
 * conforming ACP client and records every read's `(line, limit)` so tests can
 * pin the EOF probe's arguments.
 */
private class SlicingFsClient(private val content: String) : ClientSessionOperations {
    val readCalls = mutableListOf<Pair<UInt?, UInt?>>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        readCalls += (line to limit)
        val lines = rawVisualLines(content)
        val from = ((line?.toInt() ?: 1) - 1).coerceIn(0, lines.size)
        val to = limit?.let { (from + it.toInt()).coerceIn(0, lines.size) } ?: lines.size
        return ReadTextFileResponse(lines.subList(from, to).joinToString(""))
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse =
        WriteTextFileResponse()
}

private class RecordingClient : ClientSessionOperations {
    val readCalls = mutableListOf<String>()
    val writeCalls = mutableListOf<String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        readCalls += path
        return ReadTextFileResponse("content of $path")
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        writeCalls += path
        return WriteTextFileResponse()
    }
}
