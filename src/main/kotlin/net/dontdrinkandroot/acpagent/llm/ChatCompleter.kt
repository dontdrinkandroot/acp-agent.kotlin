package net.dontdrinkandroot.acpagent.llm

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterChatCompletionStreamResponse
import kotlinx.coroutines.flow.Flow

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