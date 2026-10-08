package net.dontdrinkandroot.acpagent.providerrouting

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.llm.OpenRouterEndpoint
import net.dontdrinkandroot.acpagent.llm.ProviderMaxPrice
import net.dontdrinkandroot.acpagent.llm.ProviderPreferences

private val logger = KotlinLogging.logger {}

/**
 * Converts a USD per token price into USD per million tokens, the unit the
 * OpenRouter `max_price` object expects.
 */
private const val PER_MILLION_TOKEN = 1e6

/**
 * A provider endpoint of one model, offered as a manual `provider` config
 * option: [tag] is the OpenRouter provider slug for the `provider.order`
 * routing preferences, [name] the human-readable display name.
 */
internal data class ProviderOption(val tag: String, val name: String)

/**
 * The automatic OpenRouter provider routing policy: sort providers by
 * throughput and cap the accepted completion price at the median completion
 * price of the selected model's endpoints. The same cached per-model feed
 * also backs the manual `provider` config option (slug + display name); that
 * option is independent of [enabled], which only gates the auto policy. The
 * feature is cleanly separated behind a single toggle so it can be removed or
 * disabled without touching the agent's prompt logic.
 */
internal class ProviderRouting private constructor(
    private val enabled: Boolean,
    private val endpointsFetcher: EndpointsFetcher,
) {
    private val cacheMutex = Mutex()
    private val endpointsByModel = mutableMapOf<String, List<OpenRouterEndpoint>>()

    constructor(enabled: Boolean, llm: LlmClient) : this(enabled, EndpointsFetcher(llm::fetchEndpoints))

    /** The endpoints feed of one model, or null when the feed is unavailable. */
    internal fun interface EndpointsFetcher {
        suspend fun fetchEndpoints(modelId: String): List<OpenRouterEndpoint>
    }

    /**
     * Returns the provider preferences for a model call, or null when the
     * feature is disabled or the endpoints feed is unavailable (fail open:
     * transient errors must never break a turn). Failed lookups are not cached
     * so a later call can recover.
     */
    suspend fun providerFor(model: String): ProviderPreferences? {
        if (!enabled) return null
        val endpoints = cachedEndpoints(model) ?: return null
        // Unpriceable endpoints carry no median signal: skip them instead of
        // failing, and fail open when none is priceable (a $0/m cap would
        // exclude every provider and max_price is enforced fail-closed
        // server-side).
        val prices = endpoints.mapNotNull { it.pricing.completion.toDoubleOrNull() }
        if (prices.isEmpty()) {
            logger.warn { "Provider routing: no parseable completion prices for $model" }
            return null
        }
        val median = medianCompletionPriceUsdPerMillion(prices)
        return ProviderPreferences(sort = "throughput", maxPrice = ProviderMaxPrice(completion = median))
    }

    /**
     * The selectable provider endpoints of a model, or null when the feed is
     * unavailable. Independent of the auto routing toggle - a manual pick is
     * not the auto policy. Fetched on demand and cached like the auto routing
     * median. Duplicate tags (multiple endpoints of one provider) collapse to
     * the first, so every select value stays unique.
     */
    suspend fun providersFor(model: String): List<ProviderOption>? {
        val endpoints = cachedEndpoints(model) ?: return null
        return endpoints.map { ProviderOption(it.tag, it.providerName) }.distinctBy { it.tag }
    }

    private suspend fun cachedEndpoints(model: String): List<OpenRouterEndpoint>? {
        cacheMutex.withLock { endpointsByModel[model] }?.let { return it }
        val endpoints = loadEndpoints(model) ?: return null
        cacheMutex.withLock { endpointsByModel[model] = endpoints }
        return endpoints
    }

    /**
     * One uncached fetch, fail-open: errors and empty feeds return null
     * instead of poisoning the cache (an empty feed would produce a $0/m
     * completion cap and exclude every provider; since `max_price` is enforced
     * fail-closed server-side, that would break every request).
     */
    private suspend fun loadEndpoints(model: String): List<OpenRouterEndpoint>? {
        val endpoints = runCatching { endpointsFetcher.fetchEndpoints(model) }.getOrElse {
            logger.warn(it) { "Provider routing: failed to fetch endpoints for $model" }
            return null
        }
        if (endpoints.isEmpty()) {
            logger.warn { "Provider routing: no provider endpoints listed for $model" }
            return null
        }
        return endpoints
    }

    internal companion object {
        /** Test factory over a stub fetcher. */
        internal fun createForTesting(enabled: Boolean, fetcher: EndpointsFetcher): ProviderRouting =
            ProviderRouting(enabled, fetcher)
    }
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
