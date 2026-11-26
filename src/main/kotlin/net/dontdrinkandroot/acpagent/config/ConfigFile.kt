package net.dontdrinkandroot.acpagent.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.nio.file.Path

/**
 * The parsed `config.json` (issue #50): one level of groups, tool-specific
 * options as subgroups (`tools.bash.timeoutSeconds`). All fields are nullable -
 * an absent key means "not configured", never "false" or "default"; the default
 * is applied by the layering in [Config.load].
 *
 * Parsing is strict (typed values, ranges) and fail-loudly: a wrong type or an
 * invalid value names the JSON path. Unknown keys are ignored silently, so an
 * older agent reading a newer file must not die.
 */
public data class ConfigFile(
    val openRouter: OpenRouter? = null,
    val tools: Tools? = null,
    val mcp: Mcp? = null,
    val permissions: Permissions? = null,
    val scratchDir: String? = null,
    val sandbox: Sandbox? = null,
) {
    public data class OpenRouter(
        val apiKeyFile: String? = null,
        val defaultModel: String? = null,
        val baseUrl: String? = null,
        val autoThroughputSorting: Boolean? = null,
    )

    public data class Tools(
        val maxTurnRequests: Int? = null,
        val fsProxy: Boolean? = null,
        val bash: Bash? = null,
        val webFetch: WebFetch? = null,
    ) {
        public data class Bash(val timeoutSeconds: Int? = null)

        public data class WebFetch(val allowPrivate: Boolean? = null)
    }

    public data class Mcp(val trustAnnotations: Boolean? = null)

    public data class Permissions(
        val readableDirectories: List<String>? = null,
        val writableDirectories: List<String>? = null,
        val unlistedPaths: UnlistedPaths? = null,
    )

    /**
     * The `sandbox` group, read by the sandbox supervisor only (the agent's [Config] carries
     * no sandbox field). Validated for **shape only** - types, absolute-path *form*, list
     * values. Every check that needs a host fact (creating `stateDir`, probing cache dirs,
     * mount sources existing) belongs to the sandbox plan resolution on the host: the host
     * `config.json` is mounted read-only into the container, so a host path such as
     * `stateDir` must never be able to fault the agent there.
     */
    public data class Sandbox(
        val network: String? = null,
        val stateDir: String? = null,
        val mountCaches: Boolean? = null,
        val homeVolume: String? = null,
        val extraArgs: List<String>? = null,
        val capAdd: List<String>? = null,
        val rwRootfs: Boolean? = null,
    )

    public companion object {

        /** Range for `tools.bash.timeoutSeconds`. */
        internal val BASH_TIMEOUT_RANGE = 1..3600

        /** Range for `tools.maxTurnRequests`. */
        internal val MAX_TURN_REQUESTS_RANGE = 1..Int.MAX_VALUE

        public fun parse(text: String): ConfigFile {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("not valid JSON: ${e.message}", e)
            }
            val rootObject = root as? JsonObject
                ?: throw IllegalArgumentException("top level must be a JSON object")
            return ConfigFile(
                openRouter = rootObject.group("openRouter")?.let {
                    OpenRouter(
                        apiKeyFile = it.stringValue("openRouter", "apiKeyFile"),
                        defaultModel = it.stringValue("openRouter", "defaultModel"),
                        baseUrl = it.stringValue("openRouter", "baseUrl"),
                        autoThroughputSorting = it.booleanValue("openRouter", "autoThroughputSorting"),
                    )
                },
                tools = rootObject.group("tools")?.let {
                    Tools(
                        maxTurnRequests = it.intValue("tools", "maxTurnRequests", MAX_TURN_REQUESTS_RANGE),
                        fsProxy = it.booleanValue("tools", "fsProxy"),
                        bash = it.group("bash")?.let { bash ->
                            Tools.Bash(
                                timeoutSeconds = bash.intValue("tools.bash", "timeoutSeconds", BASH_TIMEOUT_RANGE),
                            )
                        },
                        webFetch = it.group("webFetch")?.let { webFetch ->
                            Tools.WebFetch(allowPrivate = webFetch.booleanValue("tools.webFetch", "allowPrivate"))
                        },
                    )
                },
                mcp = rootObject.group("mcp")?.let {
                    Mcp(trustAnnotations = it.booleanValue("mcp", "trustAnnotations"))
                },
                permissions = rootObject.group("permissions")?.let {
                    Permissions(
                        readableDirectories = it.pathListValue("permissions", "readableDirectories"),
                        writableDirectories = it.pathListValue("permissions", "writableDirectories"),
                        unlistedPaths = it.stringValue("permissions", "unlistedPaths")
                            ?.let { value -> parseUnlistedPaths("permissions.unlistedPaths", value) },
                    )
                },
                scratchDir = rootObject.stringValue("", "scratchDir")
                    ?.let { requireScratchDir(it, "scratchDir") },
                sandbox = rootObject.group("sandbox")?.let {
                    Sandbox(
                        network = it.nonBlankStringValue("sandbox", "network"),
                        stateDir = it.stringValue("sandbox", "stateDir")
                            ?.let { value -> requireAbsoluteMountPath("sandbox.stateDir", value) },
                        mountCaches = it.booleanValue("sandbox", "mountCaches"),
                        homeVolume = it.nonBlankStringValue("sandbox", "homeVolume"),
                        extraArgs = it.stringListValue("sandbox", "extraArgs", allowBlank = false),
                        capAdd = it.stringListValue("sandbox", "capAdd", allowBlank = false),
                        rwRootfs = it.booleanValue("sandbox", "rwRootfs"),
                    )
                },
            )
        }

    }
}

/**
 * The typed JSON accessors shared by the global ([ConfigFile.parse]) and the strict
 * project-file parser: both must apply the same value rules (a path is a path), so
 * only the *key policy* differs between the two files.
 */
internal fun JsonObject.group(key: String): JsonObject? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonObject ?: throw IllegalArgumentException("$key must be an object")
}

internal fun JsonObject.stringValue(path: String, key: String): String? =
    primitive(path, key)?.also {
        if (!it.isString) throw IllegalArgumentException("${qualified(path, key)} must be a string")
    }?.content

internal fun JsonObject.nonBlankStringValue(path: String, key: String): String? =
    stringValue(path, key)?.also {
        if (it.isBlank()) throw IllegalArgumentException("${qualified(path, key)} must not be blank")
    }?.trim()

/** An array of strings, trimmed; [allowBlank] entries are kept only when true. */
internal fun JsonObject.stringListValue(path: String, key: String, allowBlank: Boolean = true): List<String>? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val array = value as? JsonArray
        ?: throw IllegalArgumentException("${qualified(path, key)} must be an array of strings")
    return array.mapIndexed { index, item ->
        val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("${qualified(path, key)}[$index] must be a string")
        val trimmed = text.trim()
        if (!allowBlank && trimmed.isEmpty()) {
            throw IllegalArgumentException("${qualified(path, key)}[$index] must not be blank")
        }
        trimmed
    }
}

internal fun JsonObject.booleanValue(path: String, key: String): Boolean? =
    primitive(path, key)?.let {
        if (it.isString) throw IllegalArgumentException("${qualified(path, key)} must be a boolean")
        it.booleanOrNull ?: throw IllegalArgumentException("${qualified(path, key)} must be a boolean")
    }

internal fun JsonObject.intValue(path: String, key: String, range: IntRange): Int? =
    primitive(path, key)?.let {
        if (it.isString) throw IllegalArgumentException("${qualified(path, key)} must be a number")
        val value = it.intOrNull
            ?: throw IllegalArgumentException("${qualified(path, key)} must be a number")
        if (value !in range) {
            throw IllegalArgumentException(
                "${qualified(path, key)} must be ${rangeText(range)}, got $value"
            )
        }
        value
    }

/** An array of absolute paths: absolute-only, no globbing, no `~`, deduped. */
internal fun JsonObject.pathListValue(path: String, key: String): List<String>? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val array = value as? JsonArray
        ?: throw IllegalArgumentException("${qualified(path, key)} must be an array of paths")
    val seen = mutableSetOf<String>()
    return array.mapIndexedNotNull { index, item ->
        val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("${qualified(path, key)}[$index] must be a string")
        requireAbsolutePath(qualified(path, key), text).takeIf { seen.add(it) }
    }
}

internal fun JsonObject.primitive(path: String, key: String): JsonPrimitive? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonPrimitive ?: throw IllegalArgumentException("${qualified(path, key)} must be a value")
}

internal fun qualified(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

/**
 * Renders an accepted numeric range for an error message (`between 1 and 3600`,
 * `at least 1`).
 */
internal fun rangeText(range: IntRange): String =
    if (range.last == Int.MAX_VALUE) "at least ${range.first}" else "between ${range.first} and ${range.last}"

/** `permissions.unlistedPaths`: what happens to paths outside cwd and all directory lists. */
public enum class UnlistedPaths {
    /** Ask via `session/request_permission` (today's behavior). */
    PROMPT,

    /** Hard-refuse, no prompt; the fix is editing the directory lists. */
    DENY,
}

internal fun parseUnlistedPaths(label: String, value: String): UnlistedPaths =
    UnlistedPaths.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("$label must be 'prompt' or 'deny', got '$value'")

/**
 * A mount path (`sandbox.stateDir`): absolute, never the filesystem root, trailing slashes
 * normalized. Only the *form* is checked - the directory's existence is a host fact and
 * belongs to the sandbox plan.
 */
internal fun requireAbsoluteMountPath(label: String, value: String): String {
    require(value.startsWith("/")) { "$label must be an absolute path, got '$value'" }
    val stripped = value.trimEnd('/')
    require(stripped.isNotEmpty()) { "$label must not be the filesystem root" }
    return stripped
}

/**
 * A permission tier entry: absolute (no globbing, no `~`), slash-normalized so `/srv/`
 * and `/srv` collapse to one entry, deduped by the caller. The filesystem root is
 * refused in **both** files: as a tier it would make every path prompt-free in a direct
 * run, while the sandbox cannot mount it - the two readers would disagree about the same
 * entry, so it is rejected before either of them sees it.
 */
internal fun requireAbsolutePath(label: String, value: String): String {
    require(value.startsWith("/")) { "$label entries must be absolute paths, got '$value'" }
    val normalized = value.trimEnd('/')
    require(normalized.isNotEmpty()) { "$label entries must not be the filesystem root" }
    return normalized
}

/**
 * `scratchDir` shape validation: absolute and not the filesystem root (normalized
 * first, so a "/tmp/.." spelling cannot turn the whole filesystem into a trust root).
 * The trust check against the directory's actual type/owner happens when it is
 * prepared.
 */
internal fun requireScratchDir(value: String, label: String): String {
    require(value.startsWith("/")) { "$label must be an absolute path, got '$value'" }
    val normalized = Path.of(value).normalize().toString()
    require(normalized != "/") { "$label must not be the filesystem root" }
    return normalized
}
