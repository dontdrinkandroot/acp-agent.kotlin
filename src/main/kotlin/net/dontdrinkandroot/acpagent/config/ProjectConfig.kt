package net.dontdrinkandroot.acpagent.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The project-local config directory, shared with `.ai/run.json` (issue #85). */
internal const val PROJECT_CONFIG_DIR: String = ".ai"

/** The project-local permission overlay. */
internal const val PROJECT_CONFIG_FILE: String = "ddr-acp-agent.json"

/** The overlay's cwd-relative path, for messages and the exclusion rule. */
internal const val PROJECT_CONFIG_RELATIVE_PATH: String = "$PROJECT_CONFIG_DIR/$PROJECT_CONFIG_FILE"

/**
 * The project-local permission overlay (issue #85): `<cwd>/.ai/ddr-acp-agent.json` adds
 * project-specific directories on top of the selected base configuration, so a repository
 * that legitimately needs one extra directory next to its checkout does not have to widen
 * the global lists for every project.
 *
 * The file carries **only** a `permissions` group and is therefore parsed strictly at every
 * level: unlike [ConfigFile.parse], an unknown key fails loudly instead of being ignored.
 * The reasoning is the inverse of the global file's leniency - a newer *global* file must
 * not kill an older agent that simply does not use the new setting yet, while this file's
 * only job is to widen (or narrow) one trust domain, and an ignored key would silently fail
 * to do exactly that. A key this parser does not know is a mistake, never a stray extra.
 *
 * Values reuse the global parser's rules ([pathListValue], [parseUnlistedPaths]), so a
 * permission path means the same thing in both files; the filesystem root is refused in
 * both.
 */
internal data class ProjectConfigSource(
    /** The file the overlay was read from - the only place that names it. */
    val file: Path,
    val permissions: ConfigFile.Permissions,
)

/**
 * Parses the overlay's text. Errors carry the JSON path only, like [ConfigFile.parse];
 * [readProjectConfig] prefixes the file so the user sees both.
 *
 * The result keeps [ConfigFile.Permissions]' nullability: an absent key means "keep the
 * base value", which is what the merge in [mergePermissionTiers] needs.
 */
internal fun parseProjectPermissions(text: String): ConfigFile.Permissions {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("not valid JSON: ${e.message}", e)
    }
    val rootObject = root as? JsonObject
        ?: throw IllegalArgumentException("top level must be a JSON object")
    rootObject.keys.forEach { key ->
        if (key == "permissions") return@forEach
        // A key of the global file that this overlay deliberately does not carry: name the
        // place it belongs instead of calling it unknown, so a copied-over config block leads
        // the user to the right file.
        if (key in GLOBAL_ONLY_ROOT_KEYS) {
            throw IllegalArgumentException(
                "'$key' cannot be set in a project file: only permissions.* is project-overridable " +
                        "(configure '$key' in ${Config.CONFIG_FILE_NAME})"
            )
        }
        throw IllegalArgumentException("unknown key '$key': a project file may only carry a permissions group")
    }
    val permissions = rootObject.group("permissions") ?: return ConfigFile.Permissions()
    permissions.keys.forEach { key ->
        if (key !in PROJECT_PERMISSION_KEYS) {
            throw IllegalArgumentException(
                "unknown key 'permissions.$key': only ${PROJECT_PERMISSION_KEYS.joinToString(", ")} " +
                        "can be set in a project file"
            )
        }
    }
    return ConfigFile.Permissions(
        readableDirectories = permissions.pathListValue("permissions", "readableDirectories"),
        writableDirectories = permissions.pathListValue("permissions", "writableDirectories"),
        unlistedPaths = permissions.stringValue("permissions", "unlistedPaths")
            ?.let { value -> parseUnlistedPaths("permissions.unlistedPaths", value) },
    )
}

/** The keys of the global file's root that a project file may not shadow. */
private val GLOBAL_ONLY_ROOT_KEYS: Set<String> =
    setOf("openRouter", "tools", "mcp", "scratchDir", "sandbox")

/** The keys the overlay may carry inside its `permissions` group. */
private val PROJECT_PERMISSION_KEYS: Set<String> =
    setOf("readableDirectories", "writableDirectories", "unlistedPaths")

/**
 * Reads the overlay from the project directory. A missing file means "no change"
 * (`null`); everything else fails loudly (unreadable file, wrong type, unknown key, a
 * relative path, a named directory that is not there).
 *
 * The directory check is deliberately stricter than [Config.directoryWarnings]: the
 * overlay is checked in with the repository, so a path it names is a claim about *this*
 * checkout - a missing one is a broken file, not a mount another machine lacks. It runs
 * after the parse (the value rules) and needs the filesystem, which is why it lives here
 * and not in the pure parser.
 */
internal fun readProjectConfig(cwd: Path): ProjectConfigSource? {
    val file = cwd.resolve(PROJECT_CONFIG_DIR).resolve(PROJECT_CONFIG_FILE)
    if (!Files.exists(file)) return null
    require(Files.isRegularFile(file)) { "project file '$file' is not a regular file" }
    val text = runCatching { File(file.toString()).readText() }
        .getOrElse { failure ->
            throw IllegalArgumentException("project file '$file' cannot be read: ${failure.message}", failure)
        }
    val permissions = try {
        parseProjectPermissions(text).also(::requireExistingDirectories)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("project file '$file': ${e.message}", e)
    }
    return ProjectConfigSource(file = file, permissions = permissions)
}

/** A named directory must exist: this file is part of the checkout, not the user's laptop. */
private fun requireExistingDirectories(permissions: ConfigFile.Permissions) {
    fun requireDirectories(label: String, directories: List<String>?) {
        directories.orEmpty().forEach { directory ->
            if (!Files.isDirectory(Path.of(directory))) {
                throw IllegalArgumentException("$label: '$directory' does not exist or is not a directory")
            }
        }
    }
    requireDirectories("permissions.readableDirectories", permissions.readableDirectories)
    requireDirectories("permissions.writableDirectories", permissions.writableDirectories)
}
