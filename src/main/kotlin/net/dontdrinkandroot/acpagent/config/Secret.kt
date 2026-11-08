package net.dontdrinkandroot.acpagent.config

/**
 * A secret value that cannot leak by accident (issue #78).
 *
 * The wrapped [value] is only reachable through the explicit `.value`
 * accessor, while every implicit rendering path - `toString()`, and therefore
 * string interpolation (`"$secret"`), `"$config"`, data-class dumps, debugger
 * summaries and logging - prints `***`. A `Config` therefore stays freely
 * loggable, and printing a key requires a deliberate `.value` (which a
 * `grep` can audit).
 */
@JvmInline
public value class Secret(public val value: String) {

    override fun toString(): String = REDACTED

    private companion object {
        const val REDACTED = "***"
    }
}