package net.dontdrinkandroot.acpagent.sandbox

import net.dontdrinkandroot.acpagent.config.Config

const val FIXTURE_PROJECT = "/fixtures/project"
const val FIXTURE_HOME = "/fixtures/home"
const val FIXTURE_STATE_DIR = "/fixtures/state"

/**
 * Fixture facts every row is written against - `uid=1001`, `/fixtures/...` - never the
 * ambient host, so the table resolves without host I/O ([FixtureProbes] answers from a
 * declared tree).
 */
const val EXPECTED_UID = 1001
const val EXPECTED_GID = 1001
const val EXPECTED_UID_GID = "$EXPECTED_UID:$EXPECTED_GID"

val FIXTURE_FACTS = fixtureFacts()

/**
 * Builds fixture host facts. The XDG roots and the config directory derive from [home] (like
 * the shell launcher's `${XDG_*:-$HOME/...}` fallbacks and the agent's own config resolver),
 * so a row changing the home gets matching defaults - a `copy(home = ...)` would keep the old
 * derived values.
 */
fun fixtureFacts(
    home: String = FIXTURE_HOME,
    cwd: String = FIXTURE_PROJECT,
    xdgCacheHome: String = "$home/.cache",
    xdgDataHome: String = "$home/.local/share",
    xdgStateHome: String = "$home/.local/state",
    configDir: String = "$home/.config/${Config.APP_CONFIG_DIR}",
    timeZone: String? = null,
    gitIdentity: GitIdentity? = null,
    toolEnv: Map<String, String> = emptyMap(),
) = HostFacts(
    uid = EXPECTED_UID,
    gid = EXPECTED_GID,
    cwd = cwd,
    home = home,
    xdgCacheHome = xdgCacheHome,
    xdgDataHome = xdgDataHome,
    xdgStateHome = xdgStateHome,
    configDir = configDir,
    term = "xterm-256color",
    timeZone = timeZone,
    gitIdentity = gitIdentity,
    toolEnv = toolEnv,
)

class PinCase(
    val name: String,
    val spec: SandboxSpec,
    /** Fixture directories (fixture-space absolute paths) the row's input assumes. */
    val fixtureDirectories: List<String> = emptyList(),
    /** Fixture files created empty; parent directories are created as needed. */
    val fixtureFiles: List<String> = emptyList(),
    val checks: ArgvExpectations.() -> Unit,
)

/**
 * Token-level assertions over a composed `docker run` argv. Deliberately token-based: the
 * shell suite joined argv with spaces, so a value containing a space was unrepresentable and
 * an assertion could match across two tokens.
 */
class ArgvExpectations(val argv: List<String>) {

    fun token(value: String) =
        failUnless(value in argv) { "expected token [$value] in argv $argv" }

    fun noToken(value: String) =
        failUnless(value !in argv) { "expected NO token [$value] in argv $argv" }

    /** Asserts a contiguous token sequence. */
    fun tokens(vararg sequence: String) {
        val start = (0..argv.size - sequence.size).firstOrNull { offset ->
            sequence.indices.all { argv[offset + it] == sequence[it] }
        }
        failUnless(start != null) { "expected token sequence ${sequence.toList()} in argv $argv" }
    }

    /** The last token: the image for rows without forwarded agent arguments. */
    fun lastToken(value: String) =
        failUnless(argv.lastOrNull() == value) { "expected the argv to end with [$value], was $argv" }

    fun valuesAfter(flag: String): List<String> =
        argv.indices.filter { argv[it] == flag }.map { argv[it + 1] }

    fun mounts(): List<String> = valuesAfter("--mount")

    fun mount(value: String) =
        failUnless(value in mounts()) { "expected mount [$value] among ${mounts()}" }

    fun noMountContaining(text: String) =
        failUnless(mounts().none { it.contains(text) }) {
            "expected no mount containing [$text] among ${mounts()}"
        }

    fun countMountsContaining(text: String): Int = mounts().count { it.contains(text) }

    fun envValues(): List<String> = valuesAfter("--env")

    fun envKeys(): List<String> = envValues().map { it.substringBefore('=') }

    fun env(key: String): String? =
        envValues().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    fun envValue(key: String, expected: String) =
        failUnless(env(key) == expected) { "expected --env $key=[$expected], was ${env(key)}" }

    fun envAbsent(key: String) =
        failUnless(env(key) == null) { "expected no --env $key, was ${env(key)}" }
}

internal fun failUnless(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}
