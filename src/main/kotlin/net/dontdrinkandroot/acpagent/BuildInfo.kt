package net.dontdrinkandroot.acpagent

import java.util.Properties

object BuildInfo {
    val commit: String by lazy {
        val properties = Properties()
        BuildInfo::class.java.getResourceAsStream("/git.properties")?.use(properties::load)
        properties.getProperty("git.commit").takeUnless { it.isNullOrBlank() } ?: "unknown"
    }
}
