package net.dontdrinkandroot.acpagent.config

import java.io.File

public data class Config(
    val openRouterApiKey: String,
    val openRouterModel: String,
    val openRouterBaseUrl: String,
    val autoThroughputSortingEnabled: Boolean = true,
    val fsProxyEnabled: Boolean = true,
    val mcpTrustAnnotations: Boolean = true,
    val bashTimeoutSeconds: Int = 600,
    val maxTurnRequests: Int = 100,
    val webFetchAllowPrivate: Boolean = false,
    /**
     * Absolute paths the agent may read (read-only path tools) without a
     * permission prompt even though they lie outside the session cwd
     * (env `ACP_EXTRA_MOUNTS`, comma-separated; set by the docker launcher from
     * the effective `ACP_DOCKER_EXTRA_MOUNTS`). Writes are unaffected and always
     * prompt outside the cwd.
     */
    val extraMounts: List<String> = emptyList(),
) {
    public companion object {
        private const val DEFAULT_MODEL = "openrouter/auto"
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_BASH_TIMEOUT_SECONDS = 600
        private const val DEFAULT_MAX_TURN_REQUESTS = 100

        /** True unless the env var is set to exactly `0`. */
        private fun Map<String, String>.flag(name: String): Boolean = this[name] != "0"

        /** The env var parsed as an int, or [default]; clamped to >= 1. */
        private fun Map<String, String>.positiveInt(name: String, default: Int): Int =
            (this[name]?.toIntOrNull() ?: default).coerceAtLeast(1)

        public fun fromEnv(env: Map<String, String> = platformEnv()): Config {
            return Config(
                openRouterApiKey = resolveApiKey(env),
                openRouterModel = env["OPENROUTER_MODEL"] ?: DEFAULT_MODEL,
                openRouterBaseUrl = env["OPENROUTER_BASE_URL"] ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = env.flag("OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED"),
                fsProxyEnabled = env.flag("FS_PROXY_ENABLED"),
                mcpTrustAnnotations = env.flag("MCP_TRUST_ANNOTATIONS"),
                bashTimeoutSeconds = env.positiveInt("ACP_BASH_TIMEOUT_SECONDS", DEFAULT_BASH_TIMEOUT_SECONDS),
                maxTurnRequests = env.positiveInt("ACP_MAX_TURN_REQUESTS", DEFAULT_MAX_TURN_REQUESTS),
                webFetchAllowPrivate = env["ACP_WEB_FETCH_ALLOW_PRIVATE"] == "1",
                extraMounts = parseExtraMounts(env["ACP_EXTRA_MOUNTS"]),
            )
        }

        /**
         * Splits a comma-separated list of absolute paths. Blank entries are
         * dropped; relative entries and duplicates are ignored fail-open (the
         * safe default stays permission-gated).
         */
        internal fun parseExtraMounts(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val seen = mutableSetOf<String>()
            return raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { it.startsWith("/") }
                .filter { seen.add(it) }
        }

        /**
         * Resolves the OpenRouter API key from `OPENROUTER_API_KEY` or
         * `OPENROUTER_API_KEY_FILE` (docker-secrets convention; the docker
         * launcher only ever sets the file variant so the key never travels
         * through the environment). Setting both is an error; a missing,
         * unreadable or empty key file fails loudly with its path.
         */
        internal fun resolveApiKey(env: Map<String, String>): String {
            val key = env["OPENROUTER_API_KEY"]
            val keyFile = env["OPENROUTER_API_KEY_FILE"]
            require(key == null || keyFile == null) {
                "Set either OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE, not both"
            }
            if (keyFile != null) {
                val content = File(keyFile).let { file ->
                    require(file.isFile) { "OPENROUTER_API_KEY_FILE $keyFile is not a readable file" }
                    runCatching { file.readText() }
                        .getOrElse { throw IllegalStateException("OPENROUTER_API_KEY_FILE $keyFile cannot be read: ${it.message}") }
                }
                val trimmed = content.trim()
                require(trimmed.isNotEmpty()) { "OPENROUTER_API_KEY_FILE $keyFile is empty" }
                return trimmed
            }
            require(!key.isNullOrBlank()) { "OPENROUTER_API_KEY is not set" }
            return key
        }
    }
}
