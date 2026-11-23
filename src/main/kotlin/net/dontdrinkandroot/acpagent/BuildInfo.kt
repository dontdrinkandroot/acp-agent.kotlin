package net.dontdrinkandroot.acpagent

import java.util.*

object BuildInfo {
    val commit: String by lazy {
        val properties = Properties()
        BuildInfo::class.java.getResourceAsStream("/git.properties")?.use(properties::load)
        properties.getProperty("git.commit").takeUnless { it.isNullOrBlank() } ?: "unknown"
    }

    /**
     * The agent version announced in `initialize` and shown by `/info`. One
     * constant for both, so the handshake and the diagnostic cannot drift.
     */
    const val version: String = "0.1.0"
}
