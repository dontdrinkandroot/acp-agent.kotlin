package net.dontdrinkandroot.acpagent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigParseTest {

    @Test
    fun `fromEnv requires api key`() {
        assertFailsWith<IllegalStateException> { Config.fromEnv(emptyMap()) }
        assertEquals("sk-x", Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "sk-x")).openRouterApiKey)
    }

    @Test
    fun `fromEnv applies defaults for model and baseUrl`() {
        val config = Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "sk-x"))
        assertEquals("openrouter/auto", config.openRouterModel)
        assertEquals("https://openrouter.ai/api/v1", config.openRouterBaseUrl)
        assertTrue(config.autoThroughputSortingEnabled, "auto throughput sorting must default to enabled")
        assertTrue(config.fsProxyEnabled, "fs proxy must default to enabled")
    }

    @Test
    fun `auto throughput sorting parsing honours the zero toggle`() {
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "1"))
                .autoThroughputSortingEnabled,
        )
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).autoThroughputSortingEnabled,
        )
        assertTrue(
            Config.fromEnv(
                mapOf(
                    "OPENROUTER_API_KEY" to "k",
                    "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "garbage"
                )
            )
                .autoThroughputSortingEnabled,
        )
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "0"))
                .autoThroughputSortingEnabled,
        )
    }

    @Test
    fun `fromEnv overrides model and baseUrl`() {
        val config = Config.fromEnv(
            mapOf(
                "OPENROUTER_API_KEY" to "sk-x",
                "OPENROUTER_MODEL" to "m/n",
                "OPENROUTER_BASE_URL" to "http://localhost:9/v1",
            )
        )
        assertEquals("m/n", config.openRouterModel)
        assertEquals("http://localhost:9/v1", config.openRouterBaseUrl)
    }

    @Test
    fun `fs proxy parsing honours the zero toggle`() {
        assertTrue(Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k")).fsProxyEnabled)
        assertTrue(
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "FS_PROXY_ENABLED" to "garbage"))
                .fsProxyEnabled,
        )
        assertEquals(
            false,
            Config.fromEnv(mapOf("OPENROUTER_API_KEY" to "k", "FS_PROXY_ENABLED" to "0"))
                .fsProxyEnabled,
        )
    }

}
