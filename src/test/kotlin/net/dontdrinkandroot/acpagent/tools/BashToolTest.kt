package net.dontdrinkandroot.acpagent.tools

import net.dontdrinkandroot.acpagent.tools.BashTool
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.tools.ToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BashToolTest {

    private val context = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
    )

    @Test
    fun `runs a command and captures stdout`() = runBlocking {
        val result = BashTool().execute(buildJsonObject { put("command", "printf 'hello'") }, context)
        assertFalse(result.isError, result.text)
        assertEquals("hello", result.text)
    }

    @Test
    fun `non-zero exit is an error with output`() = runBlocking {
        val result = BashTool().execute(buildJsonObject { put("command", "echo 'oops' >&2; exit 3") }, context)
        assertTrue(result.isError)
        assertTrue(result.text.contains("oops"), result.text)
    }

    @Test
    fun `missing command errors`() = runBlocking {
        val result = BashTool().execute(buildJsonObject {}, context)
        assertTrue(result.isError)
    }
}