package net.dontdrinkandroot.acpagent.llm

import kotlinx.coroutines.flow.Flow
import net.dontdrinkandroot.acpagent.llm.models.OpenAIMessage
import net.dontdrinkandroot.acpagent.llm.models.OpenAITool
import net.dontdrinkandroot.acpagent.llm.models.OpenRouterChatCompletionStreamResponse

/**
 * The chat-completion capability used by [net.dontdrinkandroot.acpagent.agent.PromptRunner].
 * Seams out the concrete HTTP [LlmClient] (which implements it) so the runner
 * is testable with a fake stream.
 */
public interface ChatCompleter {
    public fun chatCompletion(
        messages: List<OpenAIMessage>,
        tools: List<OpenAITool>,
        reasoning: String? = null,
        model: String = "",
        provider: ProviderPreferences? = null,
    ): Flow<OpenRouterChatCompletionStreamResponse>
}