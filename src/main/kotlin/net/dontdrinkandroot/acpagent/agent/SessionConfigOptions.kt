package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.forModel
import net.dontdrinkandroot.acpagent.providerrouting.ProviderOption
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting

private val logger = KotlinLogging.logger {}

private const val REASONING_OFF = "none"
private const val PROVIDER_AUTO = "auto"
private val DEFAULT_REASONING_LEVELS = listOf("max", "xhigh", "high", "medium", "low", "minimal")
private val REASONING_NAMES = mapOf(
    "max" to "Max",
    "xhigh" to "Extra high",
    "high" to "High",
    "medium" to "Medium",
    "low" to "Low",
    "minimal" to "Minimal",
)

private fun reasoningEffortName(effort: String): String = REASONING_NAMES[effort] ?: effort

/**
 * A feed tag usable as a select value: non-blank and distinct from the
 * [PROVIDER_AUTO] sentinel (a provider literally tagged "auto" could never be
 * selected - "auto" always means OpenRouter routing).
 */
private fun ProviderOption.selectableTag(): Boolean = tag.isNotBlank() && tag != PROVIDER_AUTO

private data class ReasoningOption(
    val value: String,
    val name: String,
    val description: String? = null,
)

private data class ReasoningSelector(
    val options: List<ReasoningOption>,
    val current: String,
)

/**
 * The per-session configuration surface: mode, model, reasoning and provider
 * options. Owns the option listing, the validation/assignment logic and the
 * effective reasoning/provider choices used on chat requests, so a new config
 * option is a new handler here instead of a widening switch in the session.
 */
@OptIn(UnstableApi::class)
internal class SessionConfigOptions(
    private val availableModes: List<SessionMode>,
    private val models: List<OpenRouterModel>,
    private val state: SessionState,
    private val providerRouting: ProviderRouting? = null,
    initialProviderOptions: List<ProviderOption>? = null,
) {

    private fun modelInfo(): OpenRouterModel? = models.forModel(state.currentModel)

    /**
     * The current model's selectable providers (null = option hidden), kept as
     * a snapshot so `options()` stays synchronous. Seeded by the session
     * assembly's prefetch and re-read from [providerRouting] on model switch.
     */
    private var providerOptions: List<ProviderOption>? = initialProviderOptions

    init {
        healStaleSlug(initialProviderOptions)
    }

    /**
     * Re-reads the provider snapshot for the current model and self-heals a
     * stale persisted slug (the model no longer lists it) back to auto.
     * Fail-open: an unavailable feed hides the option without touching the
     * selection.
     */
    suspend fun refreshProviderOptions() {
        providerOptions = providerRouting?.providersFor(state.currentModel)
        healStaleSlug(providerOptions)
    }

    /**
     * The provider slug to send on chat requests: "" (auto) routes through the
     * auto policy; a stored slug keeps applying even while the feed is
     * unavailable - an explicit pick outlives the picker being hidden.
     */
    fun effectiveProviderSlug(): String = state.providerSelection

    /**
     * A persisted slug the current model no longer lists (a stale restore or a
     * model change elsewhere) heals back to auto with a warn. Pure membership
     * check against the given snapshot; an unavailable feed (null) never
     * touches the selection - the pick outlives the picker being hidden.
     */
    private fun healStaleSlug(providers: List<ProviderOption>?) {
        val slug = state.providerSelection
        if (slug.isNotEmpty() && providers?.none { it.selectableTag() && it.tag == slug } == true) {
            logger.warn {
                "provider selection \"$slug\" is no longer listed for model \"${state.currentModel}\"; " +
                        "resetting to auto"
            }
            state.providerSelection = ""
        }
    }

    fun options(): List<SessionConfigOption> {
        val options = mutableListOf<SessionConfigOption>(
            SessionConfigOption.select(
                id = "mode",
                name = "Session Mode",
                currentValue = state.currentMode.value,
                options = SessionConfigSelectOptions.Flat(
                    availableModes.map { mode ->
                        SessionConfigSelectOption(
                            value = SessionConfigValueId(mode.id.value),
                            name = mode.name,
                            description = mode.description,
                        )
                    }
                ),
                description = "Build modifies files; Plan is read-only; Bash is build plus a permission-gated shell",
                category = SessionConfigOptionCategory.MODE,
            )
        )
        if (models.isNotEmpty()) {
            options += SessionConfigOption.select(
                id = "model",
                name = "Model",
                currentValue = state.currentModel,
                options = SessionConfigSelectOptions.Flat(
                    models.map { model ->
                        SessionConfigSelectOption(
                            value = SessionConfigValueId(model.id),
                            name = model.name ?: model.id,
                            description = model.description,
                        )
                    }
                ),
                description = "OpenRouter model used for this session",
                category = SessionConfigOptionCategory.MODEL,
            )
        }
        val reasoningSelector = reasoningSelector()
        if (reasoningSelector != null) {
            options += SessionConfigOption.select(
                id = "reasoning",
                name = "Reasoning",
                currentValue = reasoningSelector.current,
                options = SessionConfigSelectOptions.Flat(
                    reasoningSelector.options.map { option ->
                        SessionConfigSelectOption(
                            value = SessionConfigValueId(option.value),
                            name = option.name,
                            description = option.description,
                        )
                    }
                ),
                description = "Reasoning effort applied to the selected model",
                category = SessionConfigOptionCategory.THOUGHT_LEVEL,
            )
        }
        providerOptions?.let { providers ->
            options += SessionConfigOption.select(
                id = "provider",
                name = "Provider",
                currentValue = state.providerSelection.ifEmpty { PROVIDER_AUTO },
                options = SessionConfigSelectOptions.Flat(
                    buildList {
                        add(
                            SessionConfigSelectOption(
                                value = SessionConfigValueId(PROVIDER_AUTO),
                                name = "Auto",
                                description = "OpenRouter routes the request (throughput-sorted, median price cap)",
                            )
                        )
                        providers.filter { it.selectableTag() }.forEach { provider ->
                            add(
                                SessionConfigSelectOption(
                                    value = SessionConfigValueId(provider.tag),
                                    name = provider.name.ifBlank { provider.tag },
                                )
                            )
                        }
                    }
                ),
                description = "Provider used for the selected model (auto = OpenRouter routing)",
                category = SessionConfigOptionCategory("provider"),
            )
        }
        return options
    }

    /**
     * Applies a config option value, failing with invalid-params on unknown
     * options, wrong value shapes or out-of-range values.
     */
    fun apply(configId: SessionConfigId, value: SessionConfigOptionValue) {
        when (configId.value) {
            "mode" -> {
                val modeId = SessionModeId(value.stringValue("mode"))
                if (availableModes.none { it.id == modeId }) {
                    jsonRpcInvalidParams("unknown mode \"${modeId.value}\"")
                }
                state.requestMode(modeId)
            }

            "model" -> applyModel(value.stringValue("model"))

            "reasoning" -> {
                val effort = value.stringValue("reasoning")
                val selector = reasoningSelector()
                    ?: jsonRpcInvalidParams("model \"${state.currentModel}\" does not expose a reasoning option")
                if (selector.options.none { it.value == effort }) {
                    jsonRpcInvalidParams("unknown reasoning value \"$effort\"")
                }
                state.reasoningSelection = effort
            }

            "provider" -> {
                val slug = value.stringValue("provider")
                val providers = providerOptions
                    ?: jsonRpcInvalidParams("model \"${state.currentModel}\" does not expose a provider option")
                if (slug != PROVIDER_AUTO && providers.none { it.selectableTag() && it.tag == slug }) {
                    jsonRpcInvalidParams("unknown provider \"$slug\"")
                }
                state.providerSelection = if (slug == PROVIDER_AUTO) "" else slug
            }

            else -> jsonRpcInvalidParams("unknown config option \"${configId.value}\"")
        }
    }

    fun applyModel(modelId: String) {
        if (modelId.isBlank()) jsonRpcInvalidParams("model id must not be empty")
        state.currentModel = modelId
        state.reasoningSelection = ""
        state.providerSelection = ""
        providerOptions = null
    }

    private fun SessionConfigOptionValue.stringValue(optionId: String): String =
        (this as? SessionConfigOptionValue.StringValue)?.value
            ?: jsonRpcInvalidParams("config option \"$optionId\" expects a string value")

    /**
     * The configured reasoning selector for the current model, or null when the
     * model does not expose a reasoning block. The "" sentinel means "not yet
     * chosen"; the effective effort falls back to the model's default.
     */
    private fun reasoningSelector(): ReasoningSelector? {
        val capability = modelInfo()?.reasoning ?: return null
        val options = (capability.supportedEfforts?.takeIf { it.isNotEmpty() } ?: DEFAULT_REASONING_LEVELS)
            .map { level -> ReasoningOption(level, reasoningEffortName(level)) }
            .toMutableList()
        if (!capability.mandatory) {
            options += ReasoningOption(REASONING_OFF, "Off", "Disable reasoning; falls back to the model default")
        }
        val current = sequenceOf(
            state.reasoningSelection.takeIf { it.isNotBlank() },
            capability.defaultEffort,
            REASONING_OFF,
        ).firstOrNull { candidate -> options.any { it.value == candidate } }
            ?: options.first().value
        return ReasoningSelector(options, current)
    }

    /**
     * The reasoning effort to send on the next chat request: `null` when
     * reasoning is off or unsupported (the request field is omitted).
     */
    fun effectiveReasoning(): String? {
        val selector = reasoningSelector() ?: return null
        return selector.current.takeIf { it != REASONING_OFF }
    }
}
