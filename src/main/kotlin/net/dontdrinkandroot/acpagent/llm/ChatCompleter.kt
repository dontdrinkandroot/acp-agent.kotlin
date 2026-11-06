package net.dontdrinkandroot.acpagent.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.llm.models.FunctionTool

/**
 * The Responses-API capability used by [net.dontdrinkandroot.acpagent.agent.PromptRunner].
 * Seams out the concrete HTTP [LlmClient] (which implements it) so the runner
 * is testable with a fake stream.
 *
 * [input] is the full conversation history as raw item objects (verbatim
 * replay - see [ResponsesRequest]); the flow emits the raw SSE event objects
 * of the stream, which the consumer inspects field-wise (`response.*`
 * types). Raw events keep unknown event types skippable instead of failing on
 * an unknown discriminator.
 */
public interface ChatCompleter {
    public fun responsesStream(
        input: List<JsonObject>,
        tools: List<FunctionTool>,
        reasoning: String? = null,
        model: String = "",
        provider: ProviderPreferences? = null,
    ): Flow<JsonObject>
}
