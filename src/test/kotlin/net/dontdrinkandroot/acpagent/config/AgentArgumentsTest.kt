package net.dontdrinkandroot.acpagent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentArgumentsTest {

    @Test
    fun `no arguments select the default profile`() {
        assertEquals(AgentArguments(), parseAgentArguments(emptyList()))
    }

    @Test
    fun `profile selects a json file name`() {
        assertEquals("work.json", parseAgentArguments(listOf("--profile", "work")).configFileName)
    }

    @Test
    fun `a json suffix is rejected so the suffix cannot double`() {
        assertEquals(
            "--profile takes a profile name, not a file name; pass 'work' instead of 'work.json'",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profile", "work.json")) }.message,
        )
    }

    @Test
    fun `an unknown argument is an error naming it`() {
        assertEquals(
            "unknown argument '--profil'; the agent accepts only --profile <name>",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profil", "work")) }.message,
        )
        // The removed launcher flag is not special-cased either.
        assertEquals(
            "unknown argument '--skip-pull'; the agent accepts only --profile <name>",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--skip-pull")) }.message,
        )
    }

    @Test
    fun `a missing or duplicated profile value is an error`() {
        assertEquals(
            "--profile requires a profile name",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profile")) }.message,
        )
        assertEquals(
            "--profile may be given only once",
            assertFailsWith<IllegalArgumentException> {
                parseAgentArguments(listOf("--profile", "work", "--profile", "home"))
            }.message,
        )
        assertEquals(
            "--profile requires a profile name, got '--rebuild'",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profile", "--rebuild")) }.message,
        )
    }

    @Test
    fun `a profile is a bare name, never a path`() {
        assertEquals(
            "--profile must be a profile name, not a path, got '../work'",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profile", "../work")) }.message,
        )
        assertEquals(
            "--profile must be a profile name, got '..'",
            assertFailsWith<IllegalArgumentException> { parseAgentArguments(listOf("--profile", "..")) }.message,
        )
    }
}
