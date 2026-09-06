package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel

private const val REASONING_OFF = "none"
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
 * The per-session configuration surface: mode, model and reasoning options.
 * Owns the option listing, the validation/assignment logic and the effective
 * reasoning effort used on chat requests, so a new config option is a new
 * handler here instead of a widening switch in the session.
 */
@OptIn(UnstableApi::class)
internal class SessionConfigOptions(
    private val availableModes: List<SessionMode>,
    private val models: List<OpenRouterModel>,
    private val state: SessionState,
) {

    private fun modelInfo(): OpenRouterModel? = models.firstOrNull { it.id == state.currentModel }

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
                state.currentMode = modeId
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

            else -> jsonRpcInvalidParams("unknown config option \"${configId.value}\"")
        }
    }

    fun applyModel(modelId: String) {
        if (modelId.isBlank()) jsonRpcInvalidParams("model id must not be empty")
        state.currentModel = modelId
        state.reasoningSelection = ""
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
        var current = state.reasoningSelection
        if (options.none { it.value == current }) {
            current = capability.defaultEffort ?: ""
            if (options.none { it.value == current }) {
                current = REASONING_OFF
                if (options.none { it.value == current }) {
                    current = options.first().value
                }
            }
        }
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
