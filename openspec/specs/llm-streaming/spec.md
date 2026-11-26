# llm-streaming Specification

## Purpose

Defines how the agent consumes OpenRouter's Responses API over SSE: which events it relays as
what, how completions and failures are detected, and the shape of the requests it sends.

## Requirements

### Requirement: The stream is read as an SSE event scan
The agent SHALL read the response as server-sent events terminated by the `[DONE]` sentinel or
a terminal response event, and SHALL relay events as raw objects so an unknown event type is
skipped instead of failing the stream.

#### Scenario: An unknown event type does not break the stream
- **WHEN** the provider emits an event type the agent does not know
- **THEN** the stream continues and the turn is unaffected
- **Pinned by** `PromptRunnerTest` (unit)

#### Scenario: A stream without a terminal event is a loud failure
- **WHEN** the connection drops before `[DONE]` or a terminal response event
- **THEN** the turn fails with a model error instead of executing partial tool calls or ending silently
- **Pinned by** `LlmClientErrorTest` (unit)

### Requirement: Reasoning and reply deltas are relayed live
Reasoning deltas SHALL be relayed as agent thought chunks and reply deltas as agent message
chunks, each non-empty delta immediately. Empty deltas SHALL NOT produce a chunk.

#### Scenario: Reasoning is visible while it streams
- **WHEN** the model streams reasoning deltas
- **THEN** the client receives thought chunks in order
- **Pinned by** `E2eWireConformanceTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: Empty deltas are swallowed
- **WHEN** the provider interleaves empty content deltas with reasoning
- **THEN** no blank message chunk is emitted
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: Output items and tool-call arguments are accumulated
`response.output_item.added` and `response.output_item.done` SHALL accumulate the iteration's
items by output index - reasoning, assistant message and function call - and argument deltas
SHALL be merged into the function call they belong to.

#### Scenario: A tool call is assembled from deltas
- **WHEN** the function-call arguments arrive in several deltas
- **THEN** the executed call carries the complete argument string
- **Pinned by** `E2eWireConformanceTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: Several calls in one iteration stay separate
- **WHEN** the provider emits two function calls
- **THEN** both execute, in the order they were produced
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: Terminal and error events are classified
`response.completed` and `response.incomplete` SHALL end the stream normally, carrying status,
incomplete reason and usage. `response.failed` and a top-level error object SHALL raise a
model error carrying the provider's message, as SHALL an HTTP error status.

#### Scenario: A failed response is a model error
- **WHEN** the stream carries a `response.failed` event
- **THEN** the turn fails with the provider's message
- **Pinned by** `LlmClientErrorTest` (unit)

#### Scenario: An HTTP error is a model error
- **WHEN** the endpoint answers with a non-success status
- **THEN** the turn fails with a message carrying the status and a truncated body
- **Pinned by** `LlmClientErrorTest` (unit)

#### Scenario: An incomplete response carries its reason
- **WHEN** the stream ends with `response.incomplete`
- **THEN** the incomplete reason and usage reach the turn logic
- **Pinned by** `LlmResponseGoldensTest` (unit), `PromptRunnerTest` (unit)

### Requirement: The request shape is a closed contract
Model requests SHALL carry the model, the input items, the offered tools (omitted when there
are none), the reasoning effort (omitted when off) and the provider preferences (omitted when
absent). They SHALL NOT carry `temperature`, `max_output_tokens`, `store`,
`previous_response_id`, `include` or `reasoning.context`.

#### Scenario: A request carries only the agreed fields
- **WHEN** a model request is sent
- **THEN** its body holds exactly the agreed members and none of the forbidden ones
- **Pinned by** `LlmResponseGoldensTest` (unit)

#### Scenario: No tools means no tools member
- **WHEN** the request is built for a text-only synthesis pass
- **THEN** the body carries no `tools` member
- **Pinned by** `LlmResponseGoldensTest` (unit), `PromptRunnerTest` (unit)

### Requirement: Requests identify the application
Every model request SHALL carry the application attribution headers and the API key as a
bearer token.

#### Scenario: Attribution headers on the wire
- **WHEN** a model request is sent
- **THEN** it carries `HTTP-Referer` and `X-OpenRouter-Title` and an `Authorization` bearer header
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: A silent stream fails instead of hanging
The agent SHALL NOT apply an aggregate request deadline to a model request, because reasoning
and tool loops legitimately stream longer; a dead connection SHALL instead be caught by a
per-connection idle timeout, so a silent provider fails rather than waiting forever.

#### Scenario: A long stream is not killed
- **WHEN** a provider streams a completion for longer than the engine's default aggregate deadline
- **THEN** the request is not aborted by a client-side deadline
- **Pinned by** none - manual validation only (needs a live provider streaming for longer than 15 s)

#### Scenario: A silent stream fails loudly
- **WHEN** the provider goes silent mid-stream
- **THEN** the request fails after the idle timeout instead of waiting forever
- **Pinned by** none - manual validation only (needs a provider that stops sending mid-stream)
