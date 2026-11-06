package net.dontdrinkandroot.acpagent.mcp

import com.agentclientprotocol.model.EnvVariable
import kotlin.test.Test
import kotlin.test.assertEquals

class McpConnectorTest {

    private val agentEnv = mapOf(
        "PATH" to "/usr/local/bin:/usr/bin",
        "HOME" to "/home/dev",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
        "TZ" to "Europe/Berlin",
        "DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE" to "/home/dev/.config/ddr-acp-agent/openrouter.key",
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES" to "/srv/data",
        "DDR_ACP_AGENT_SCRATCH_DIR" to "/tmp/ddr-acp-agent",
        "GITHUB_TOKEN" to "ghp_secret",
        "SSH_AUTH_SOCK" to "/run/user/1000/ssh-agent.sock",
        "AWS_SECRET_ACCESS_KEY" to "aws-secret",
        "SOME_IDE_INTERNAL" to "internal",
    )

    @Test
    fun `the child environment carries the process basics`() {
        val env = mcpChildEnvironment(agentEnv, emptyList())

        assertEquals("/usr/local/bin:/usr/bin", env["PATH"])
        assertEquals("/home/dev", env["HOME"])
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("Europe/Berlin", env["TZ"])
    }

    @Test
    fun `secrets and agent configuration never reach a server`() {
        val env = mcpChildEnvironment(agentEnv, emptyList())

        for (leaked in listOf(
            "DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE",
            "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES",
            "DDR_ACP_AGENT_SCRATCH_DIR",
            "GITHUB_TOKEN",
            "SSH_AUTH_SOCK",
            "AWS_SECRET_ACCESS_KEY",
            "SOME_IDE_INTERNAL",
        )) {
            assertEquals(null, env[leaked], "'$leaked' must not be inherited by an MCP server")
        }
    }

    @Test
    fun `client supplied variables are added`() {
        val env = mcpChildEnvironment(
            agentEnv,
            listOf(EnvVariable("GITHUB_TOKEN", "ghp_from_client"), EnvVariable("IJ_MCP_PROJECT", "/project")),
        )

        assertEquals("ghp_from_client", env["GITHUB_TOKEN"])
        assertEquals("/project", env["IJ_MCP_PROJECT"])
    }

    @Test
    fun `client supplied variables win over the inherited base`() {
        val env =
            mcpChildEnvironment(agentEnv, listOf(EnvVariable("PATH", "/opt/bin"), EnvVariable("HOME", "/srv/home")))

        assertEquals("/opt/bin", env["PATH"])
        assertEquals("/srv/home", env["HOME"])
    }
}
