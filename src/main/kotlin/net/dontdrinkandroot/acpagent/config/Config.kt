package net.dontdrinkandroot.acpagent.config

public data class Config(
    val openRouterApiKey: String,
    val openRouterModel: String,
    val openRouterBaseUrl: String,
    val autoThroughputSortingEnabled: Boolean = true,
    val fsProxyEnabled: Boolean = true,
    val bashTimeoutSeconds: Int = 600,
) {
    public companion object {
        private const val DEFAULT_MODEL = "openrouter/auto"
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_BASH_TIMEOUT_SECONDS = 600

        public fun fromEnv(env: Map<String, String> = platformEnv()): Config {
            val apiKey = env["OPENROUTER_API_KEY"]
                ?: error("OPENROUTER_API_KEY is not set")

            return Config(
                openRouterApiKey = apiKey,
                openRouterModel = env["OPENROUTER_MODEL"] ?: DEFAULT_MODEL,
                openRouterBaseUrl = env["OPENROUTER_BASE_URL"] ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = env["OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED"] != "0",
                fsProxyEnabled = env["FS_PROXY_ENABLED"] != "0",
                bashTimeoutSeconds = (env["ACP_BASH_TIMEOUT_SECONDS"]?.toIntOrNull()
                    ?: DEFAULT_BASH_TIMEOUT_SECONDS).coerceAtLeast(1),
            )
        }
    }
}