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

        public fun fromEnv(env: Map<String, String> = platformEnv()): Config {
            return Config(
                openRouterApiKey = resolveApiKey(env),
                openRouterModel = env["OPENROUTER_MODEL"] ?: DEFAULT_MODEL,
                openRouterBaseUrl = env["OPENROUTER_BASE_URL"] ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = env["OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED"] != "0",
                fsProxyEnabled = env["FS_PROXY_ENABLED"] != "0",
                mcpTrustAnnotations = env["MCP_TRUST_ANNOTATIONS"] != "0",
                bashTimeoutSeconds = (env["ACP_BASH_TIMEOUT_SECONDS"]?.toIntOrNull()
                    ?: DEFAULT_BASH_TIMEOUT_SECONDS).coerceAtLeast(1),
                maxTurnRequests = (env["ACP_MAX_TURN_REQUESTS"]?.toIntOrNull()
                    ?: DEFAULT_MAX_TURN_REQUESTS).coerceAtLeast(1),
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
