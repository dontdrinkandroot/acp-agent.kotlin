package net.dontdrinkandroot.acpagent.config

import java.nio.file.Path

/** The per-application directory below the state root (`…/ddr-acp-agent`, holds `sessions/`). */
public const val APP_STATE_DIR: String = "ddr-acp-agent"

/**
 * The XDG state root: `$XDG_STATE_HOME`, else `$HOME/.local/state`.
 *
 * Shared by the session store (where records live) and the sandbox plan (which mounts that
 * same directory into the container), so the two can never resolve different roots - a
 * divergence would mount one directory while the agent wrote to another.
 */
public fun stateHome(env: Map<String, String>, home: Path): Path {
    val xdgStateHome = env["XDG_STATE_HOME"]
    return if (xdgStateHome.isNullOrBlank()) home.resolve(".local").resolve("state") else Path.of(xdgStateHome)
}
