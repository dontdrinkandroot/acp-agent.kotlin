package net.dontdrinkandroot.acpagent.llm

import kotlinx.serialization.Serializable

/**
 * Wire types for `GET /models`. Parsed with the shared [llmWireJson], so
 * snake-case field names map automatically.
 */
@Serializable
internal data class OpenRouterModelsResponse(
    val data: List<OpenRouterModel> = emptyList(),
)

@Serializable
internal data class OpenRouterModel(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val supportedParameters: List<String> = emptyList(),
    val architecture: OpenRouterModelArchitecture? = null,
    val reasoning: ReasoningCapability? = null,
    val contextLength: Int = 0,
)

@Serializable
internal data class OpenRouterModelArchitecture(
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
)

/**
 * How much reasoning control the model exposes (OpenRouter `reasoning` block).
 * A nil/empty `supportedEfforts` means all gateway effort values are accepted.
 */
@Serializable
internal data class ReasoningCapability(
    val supportedEfforts: List<String>? = null,
    val defaultEffort: String? = null,
    val mandatory: Boolean = false,
)

/**
 * Wire types for `GET /models/{author}/{slug}/endpoints`. Completion prices are
 * USD per token strings in the API; they are scaled to USD per million tokens
 * by the provider routing policy.
 */
@Serializable
internal data class OpenRouterEndpointsResponse(
    val data: OpenRouterEndpointsData = OpenRouterEndpointsData(),
)

@Serializable
internal data class OpenRouterEndpointsData(
    val endpoints: List<OpenRouterEndpoint> = emptyList(),
)

@Serializable
internal data class OpenRouterEndpoint(
    val pricing: OpenRouterEndpointPricing = OpenRouterEndpointPricing(),
)

@Serializable
internal data class OpenRouterEndpointPricing(
    val completion: String = "",
)