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

    public companion object {

        /** Range for `tools.bash.timeoutSeconds`, shared with the env layer. */
        internal val BASH_TIMEOUT_RANGE = 1..3600

        /** Range for `tools.maxTurnRequests`, shared with the env layer. */
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
            )
        }

        /** The sub-object at [key], or null when absent; a non-object is an error. */
        private fun JsonObject.group(key: String): JsonObject? {
            val value = this[key] ?: return null
            if (value is JsonNull) return null
            return value as? JsonObject ?: throw IllegalArgumentException("$key must be an object")
        }

        private fun JsonObject.stringValue(path: String, key: String): String? =
            primitive(path, key)?.also {
                if (!it.isString) throw IllegalArgumentException("${qualified(path, key)} must be a string")
            }?.content

        private fun JsonObject.booleanValue(path: String, key: String): Boolean? =
            primitive(path, key)?.let {
                if (it.isString) throw IllegalArgumentException("${qualified(path, key)} must be a boolean")
                it.booleanOrNull ?: throw IllegalArgumentException("${qualified(path, key)} must be a boolean")
            }

        private fun JsonObject.intValue(path: String, key: String, range: IntRange): Int? =
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
        private fun JsonObject.pathListValue(path: String, key: String): List<String>? {
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

        private fun JsonObject.primitive(path: String, key: String): JsonPrimitive? {
            val value = this[key] ?: return null
            if (value is JsonNull) return null
            return value as? JsonPrimitive ?: throw IllegalArgumentException("${qualified(path, key)} must be a value")
        }

        private fun qualified(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"
    }
}

/**
 * Renders an accepted numeric range for an error message (`between 1 and 3600`,
 * `at least 1`), shared by the config-file and the env layer so both layers
 * report the same bounds.
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

internal fun requireAbsolutePath(label: String, value: String): String {
    require(value.startsWith("/")) { "$label entries must be absolute paths, got '$value'" }
    return value
}

/**
 * `scratchDir` shape validation, shared by both layers: absolute and not the
 * filesystem root (normalized first, so a "/tmp/.." spelling cannot turn the
 * whole filesystem into a trust root). The trust check against the directory's
 * actual type/owner happens when it is prepared.
 */
internal fun requireScratchDir(value: String, label: String): String {
    require(value.startsWith("/")) { "$label must be an absolute path, got '$value'" }
    val normalized = Path.of(value).normalize().toString()
    require(normalized != "/") { "$label must not be the filesystem root" }
    return normalized
}
