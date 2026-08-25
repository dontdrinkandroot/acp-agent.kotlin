package net.dontdrinkandroot.acpagent.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The single Json instance for wire-shaped LLM data (chat traffic and persisted
 * conversation history). The settings are load-bearing: without the snake-case
 * naming strategy `tool_calls` / `finish_reason` silently fail to parse.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val llmWireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}