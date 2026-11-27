package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.ContentBlock
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.config.Secret
import net.dontdrinkandroot.acpagent.config.UnlistedPaths
import net.dontdrinkandroot.acpagent.spec.Pins
import kotlin.test.*

@Pins("agent-process", "/info is advertised and answered locally")
class InfoCommandTest {

    private fun config() = Config(
        openRouterApiKey = Secret("sk-super-secret-value"),
        openRouterModel = "openrouter/auto",
        openRouterBaseUrl = "https://openrouter.example/api/v1",
        readableDirectories = listOf("/opt/shared"),
        unlistedPaths = UnlistedPaths.DENY,
        scratchDir = "/tmp/scratch",
        openRouterApiKeyFile = "/home/me/.config/ddr-acp-agent/openrouter.key",
    )

    private fun snapshot(discardedText: String = "") = InfoSnapshot(
        config = config(),
        agentVersion = "0.1.0",
        buildCommit = "abc1234",
        sessionId = "sess_0000000000000001",
        cwd = "/project",
        mode = "plan",
        modeDescription = "Read-only: research the code and present an implementation plan",
        sessionModel = "test-model",
        reasoning = "high",
        provider = "auto",
        scratchDir = "/tmp/scratch",
        discardedText = discardedText,
    )

    private fun text(text: String) = listOf(ContentBlock.Text(text))

    /** The advertisement and the detector must name the same command. */
    @Test
    fun `the advertised command is the one the turn recognizes`() {
        val command = infoAvailableCommand()
        assertEquals(INFO_COMMAND_NAME, command.name)
        assertTrue(command.description.isNotBlank(), "the command needs a description")
        assertEquals(
            "",
            infoCommandRemainder(text("/${command.name}")),
            "the advertised name must be the one the turn intercepts",
        )
    }

    @Test
    fun `the bare command is recognized and discards nothing`() {
        assertEquals("", infoCommandRemainder(text("/info")))
        assertEquals("", infoCommandRemainder(text("  /info \n")))
    }

    @Test
    fun `text after the command is returned as the discarded remainder`() {
        assertEquals("now check the logs", infoCommandRemainder(text("/info now check the logs")))
        assertEquals("now", infoCommandRemainder(text("/info\nnow")))
    }

    @Test
    fun `the command name must end at a word boundary`() {
        assertNull(infoCommandRemainder(text("/infofoo")))
        assertNull(infoCommandRemainder(text("/infofoo bar")))
    }

    @Test
    fun `the command is case sensitive`() {
        assertNull(infoCommandRemainder(text("/INFO")))
        assertNull(infoCommandRemainder(text("/Info")))
    }

    @Test
    fun `prose mentioning the command is an ordinary prompt`() {
        assertNull(infoCommandRemainder(text("please run /info")))
        assertNull(infoCommandRemainder(text("run /info for me")))
    }

    @Test
    fun `extra content blocks make the prompt ordinary`() {
        assertNull(
            infoCommandRemainder(listOf(ContentBlock.Text("/info"), ContentBlock.Text("and more"))),
            "a second block could be a real request the command must not swallow",
        )
        assertNull(
            infoCommandRemainder(
                listOf(ContentBlock.Text("/info"), ContentBlock.Image(data = "AAAA", mimeType = "image/png"))
            ),
        )
        assertNull(infoCommandRemainder(emptyList()))
    }

    @Test
    fun `the dump states the effective session and config values`() {
        val rendered = renderInfo(snapshot())
        listOf(
            "- Version: 0.1.0",
            "- Build: abc1234",
            "- Id: sess_0000000000000001",
            "- Cwd: `/project`",
            "- Mode: plan (Read-only: research the code and present an implementation plan)",
            "- Base URL: https://openrouter.example/api/v1",
            "- Default model: openrouter/auto",
            "- Session model: test-model",
            "- Reasoning: high",
            "- Provider: auto",
            "- Auto throughput sorting: on",
            "- FS proxy: on",
            "- MCP trust annotations: on",
            "- Bash timeout: 600s",
            "- Max turn requests: 100",
            "- Web fetch private: off",
            "- API key: `/home/me/.config/ddr-acp-agent/openrouter.key` (owner-only)",
            "- Readable: `/opt/shared`",
            "- Writable: (none)",
            "- Unlisted paths: deny",
            "- Scratch: `/tmp/scratch`",
            "- Project file: (none)",
        ).forEach { expected -> assertContains(rendered, expected) }
    }

    @Test
    fun `the dump names the applied project overlay`() {
        // Issue #85: the merged tiers cannot show provenance by design, so the dump names
        // the overlay once - "why can this session read /opt/shared?" stays answerable.
        val withOverlay = snapshot().copy(
            config = config().copy(projectConfigFile = "/project/.ai/ddr-acp-agent.json"),
        )
        assertContains(renderInfo(withOverlay), "- Project file: `/project/.ai/ddr-acp-agent.json`")
    }

    @Test
    fun `the dump never renders the API key value`() {
        val rendered = renderInfo(snapshot())
        assertFalse(rendered.contains("sk-super-secret-value"), "the key value must never reach the output: $rendered")
        assertFalse(rendered.contains("***"), "the dump names the key file, it does not redact a value: $rendered")
    }

    @Test
    fun `a model without a reasoning option states that instead of an effort`() {
        assertContains(renderInfo(snapshot().copy(reasoning = null)), "- Reasoning: not supported by this model")
    }

    @Test
    fun `a model without a mode description still names the mode`() {
        val rendered = renderInfo(snapshot().copy(modeDescription = ""))
        assertContains(rendered, "- Mode: plan\n")
    }

    @Test
    fun `values with line breaks cannot break the block apart`() {
        val rendered = renderInfo(
            snapshot().copy(cwd = "/pro\nject", scratchDir = "/tmp\nscratch")
                .let { it.copy(config = it.config.copy(readableDirectories = listOf("/opt/a\nb"))) }
        )
        assertContains(rendered, "- Cwd: `/pro ject`")
        assertContains(rendered, "- Scratch: `/tmp scratch`")
        assertContains(rendered, "- Readable: `/opt/a b`")
        assertEquals(
            renderInfo(snapshot()).lines().size,
            rendered.lines().size,
            "a line break inside a value must not add a line to the block: $rendered",
        )
    }

    @Test
    fun `discarded text is reported in a note`() {
        val rendered = renderInfo(snapshot(discardedText = "now check the logs"))
        assertTrue(
            rendered.startsWith("**Note:** `/info` ignores additional text; discarded: \"now check the logs\""),
            "the note must lead the output, got: $rendered",
        )
    }

    @Test
    fun `the note is absent when the invocation carried no extra text`() {
        assertFalse(renderInfo(snapshot()).contains("**Note:**"))
    }

    @Test
    fun `the note flattens and caps the discarded text`() {
        val rendered = renderInfo(snapshot(discardedText = "first line\n\nsecond line"))
        assertContains(rendered, "discarded: \"first line second line\"")

        val capped = renderInfo(snapshot(discardedText = "x".repeat(500)))
        assertFalse(capped.contains("x".repeat(500)), "a long remainder must be capped")
        assertContains(capped, "x".repeat(200) + "…")
    }
}
