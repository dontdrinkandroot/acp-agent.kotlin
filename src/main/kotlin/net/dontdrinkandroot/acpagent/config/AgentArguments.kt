package net.dontdrinkandroot.acpagent.config

/**
 * The agent's command line: `--profile <name>` selects a profile from the config directory
 * (`work` -> `work.json`), nothing else is accepted.
 *
 * Parsing is strict on purpose: a mistyped `--profil work` must not silently run on the
 * default profile, and a removed launcher flag must fail loudly instead of being ignored.
 * The sandbox supervisor parses the same flag independently (it needs the same file to
 * compose the mounts) and forwards it to the containerized agent, so both sides must agree
 * on the file-name convention - [profileFileName] is the single place that defines it.
 */
public data class AgentArguments(val profile: String? = null) {

    /** The config file name the selected profile resolves to, or null for the default file. */
    public val configFileName: String? get() = profile?.let { profileFileName(it) }
}

public fun parseAgentArguments(args: List<String>): AgentArguments {
    var profile: String? = null
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        if (argument != "--profile") {
            throw IllegalArgumentException("unknown argument '$argument'; the agent accepts only --profile <name>")
        }
        require(profile == null) { "--profile may be given only once" }
        val value = args.getOrNull(index + 1)
        require(value != null) { "--profile requires a profile name" }
        require(!value.startsWith("--")) { "--profile requires a profile name, got '$value'" }
        // Validate eagerly so a bad name fails before the config is read.
        profileFileName(value)
        profile = value
        index += 2
    }
    return AgentArguments(profile)
}

/**
 * `work` -> `work.json`. A `.json`-suffixed value is rejected instead of being normalized,
 * so a doubled suffix cannot happen silently; the name is a bare file name, so it resolves
 * identically on the host and inside the container.
 */
internal fun profileFileName(profile: String): String {
    val name = profile.trim()
    require(name.isNotEmpty()) { "--profile requires a profile name, got a blank value" }
    require(!name.endsWith(".json")) {
        "--profile takes a profile name, not a file name; " +
                "pass '${name.removeSuffix(".json")}' instead of '$name'"
    }
    require(!name.contains('/')) { "--profile must be a profile name, not a path, got '$name'" }
    require(name != "." && name != "..") { "--profile must be a profile name, got '$name'" }
    return "$name.json"
}
