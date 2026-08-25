package net.dontdrinkandroot.acpagent.providerrouting

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.llm.ProviderMaxPrice
import net.dontdrinkandroot.acpagent.llm.ProviderPreferences

private val logger = KotlinLogging.logger {}

/**
 * Converts a USD per token price into USD per million tokens, the unit the
 * OpenRouter `max_price` object expects.
 */
private const val PER_MILLION_TOKEN = 1e6

/**
 * The automatic OpenRouter provider routing policy: sort providers by
 * throughput and cap the accepted completion price at the median completion
 * price of the selected model's endpoints. Results are cached per model so the
 * endpoints feed is fetched at most once per model. The feature is cleanly
 * separated behind a single toggle so it can be removed or disabled without
 * touching the agent's prompt logic.
 */
internal class ProviderRouting(
    private val enabled: Boolean,
    private val llm: LlmClient,
) {
    private val cacheMutex = Mutex()
    private val medianByModel = mutableMapOf<String, Double>()

    /**
     * Returns the provider preferences for a model call, or null when the
     * feature is disabled or the endpoints feed is unavailable (fail open:
     * transient errors must never break a turn). Failed lookups are not cached
     * so a later call can recover.
     */
    suspend fun providerFor(model: String): ProviderPreferences? {
        if (!enabled) return null
        val cached = cacheMutex.withLock { medianByModel[model] }
        if (cached != null) return preferences(cached)
        val endpoints = runCatching { llm.fetchEndpoints(model) }.getOrElse {
            logger.warn(it) { "Auto throughput sorting: failed to fetch endpoints for $model" }
            return null
        }
        if (endpoints.isEmpty()) {
            // An empty feed would produce a $0/m completion cap and exclude
            // every provider. Since max_price is enforced fail-closed
            // server-side, that would break every request; fail open instead.
            logger.warn { "Auto throughput sorting: no provider endpoints listed for $model" }
            return null
        }
        val median = medianCompletionPriceUsdPerMillion(endpoints)
        cacheMutex.withLock { medianByModel[model] = median }
        return preferences(median)
    }

    private fun preferences(medianCompletion: Double): ProviderPreferences =
        ProviderPreferences(sort = "throughput", maxPrice = ProviderMaxPrice(completion = medianCompletion))
}

/**
 * Returns the standard median of the per-token completion prices (odd count:
 * middle value; even count: average of the two middle values), scaled to USD
 * per million tokens.
 */
internal fun medianCompletionPriceUsdPerMillion(prices: List<Double>): Double {
    if (prices.isEmpty()) return 0.0
    val sorted = prices.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle] * PER_MILLION_TOKEN
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2 * PER_MILLION_TOKEN
    }
}