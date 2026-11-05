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
    /**
     * The agent's own scratch directory (env `DDR_ACP_AGENT_SCRATCH_DIR`,
     * default `/tmp/ddr-acp-agent`): read-trusted like [extraMounts], so the
     * non-mutating path tools run there without a permission prompt in every
     * mode. Deliberately *not* part of [extraMounts] - that list is the
     * user-configured one; the two are merged only where the trust roots are
     * consumed (see `Main.runAgent`).
     *
     * This is the *configured* value. Session assembly prepares it (creates +
     * verifies it) and passes the resulting real path separately, so this field
     * never becomes a trust root unverified.
     */
    val scratchDir: String = DEFAULT_SCRATCH_DIR,
) {
    public companion object {
        private const val DEFAULT_MODEL = "openrouter/auto"
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_BASH_TIMEOUT_SECONDS = 600

        /**
         * Default scratch directory. A literal, not derived from
         * `java.io.tmpdir`, so the documented path is stable; the env var
         * overrides it (e2e scenarios point it at their own fixture).
         */
        internal const val DEFAULT_SCRATCH_DIR = "/tmp/ddr-acp-agent"

        /**
         * Upper bound for [bashTimeoutSeconds]: a configured value is clamped so
         * a huge `ACP_BASH_TIMEOUT_SECONDS` cannot arm a decades-long process
         * wait (issue #25). One hour still covers cold multi-minute builds.
         */
        private const val MAX_BASH_TIMEOUT_SECONDS = 3600

        private const val DEFAULT_MAX_TURN_REQUESTS = 100

        /** True unless the env var is set to exactly `0`. */
        private fun Map<String, String>.flag(name: String): Boolean = this[name] != "0"

        /** The env var parsed as an int, or [default]; clamped to 1..[max]. */
        private fun Map<String, String>.positiveInt(name: String, default: Int, max: Int = Int.MAX_VALUE): Int =
            (this[name]?.toIntOrNull() ?: default).coerceIn(1, max)

        public fun fromEnv(env: Map<String, String> = platformEnv()): Config {
            return Config(
                openRouterApiKey = resolveApiKey(env),
                openRouterModel = env["OPENROUTER_MODEL"] ?: DEFAULT_MODEL,
                openRouterBaseUrl = env["OPENROUTER_BASE_URL"] ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = env.flag("OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED"),
                fsProxyEnabled = env.flag("FS_PROXY_ENABLED"),
                mcpTrustAnnotations = env.flag("MCP_TRUST_ANNOTATIONS"),
                bashTimeoutSeconds = env.positiveInt(
                    "ACP_BASH_TIMEOUT_SECONDS",
                    DEFAULT_BASH_TIMEOUT_SECONDS,
                    max = MAX_BASH_TIMEOUT_SECONDS,
                ),
                maxTurnRequests = env.positiveInt("ACP_MAX_TURN_REQUESTS", DEFAULT_MAX_TURN_REQUESTS),
                webFetchAllowPrivate = env["ACP_WEB_FETCH_ALLOW_PRIVATE"] == "1",
                extraMounts = parseExtraMounts(env["ACP_EXTRA_MOUNTS"]),
                scratchDir = scratchDir(env["DDR_ACP_AGENT_SCRATCH_DIR"]),
            )
        }

        /**
         * Resolves the scratch directory: the default when unset/blank, else
         * the configured value. Shape-strict only - it must be absolute and
         * must not be the filesystem root; the trust check against the
         * directory's actual type/owner happens when it is prepared.
         */
        internal fun scratchDir(raw: String?): String {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return DEFAULT_SCRATCH_DIR
            require(value.startsWith("/")) {
                "DDR_ACP_AGENT_SCRATCH_DIR must be an absolute path, got '$value'"
            }
            // Normalized before the root check, so a "/tmp/.." spelling cannot
            // slip past it and turn the whole filesystem into a trust root.
            val normalized = java.nio.file.Path.of(value).normalize().toString()
            require(normalized != "/") {
                "DDR_ACP_AGENT_SCRATCH_DIR must not be the filesystem root"
            }
            return normalized
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
