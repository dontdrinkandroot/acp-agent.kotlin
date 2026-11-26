# agent-turn Specification

## Purpose

Defines one prompt turn: how many model iterations it may take, when and in which order it
ends, how truncated or filtered completions are recovered, and how cancellation and repeated
tool calls are handled.

## Requirements

### Requirement: A prompt turn runs at most the configured number of tool iterations
One `session/prompt` SHALL run at most `tools.maxTurnRequests` tool-calling model iterations,
executing each iteration's tool calls sequentially before the next iteration is requested.

#### Scenario: The budget bounds the loop
- **WHEN** the model keeps calling tools and the configured maximum is 3
- **THEN** at most 3 tool-calling iterations run
- **Pinned by** `E2eMaxTurnRequestsTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: A turn without tool calls ends after one iteration
- **WHEN** the first iteration returns text and no tool call
- **THEN** the turn ends with stop reason `end_turn`
- **Pinned by** `PromptRunnerTest` (unit), `E2eWireConformanceTest` (e2e)

### Requirement: An exhausted budget ends with a text-only synthesis pass
When the iteration budget is consumed while the model kept calling tools, the agent SHALL
run one final synthesis iteration with no tools offered, then end the turn with stop reason
`max_turn_requests`.

#### Scenario: Exhausted budget summarizes instead of stopping abruptly
- **WHEN** the last allowed iteration again returns tool calls
- **THEN** a final text-only iteration summarizes what was done and what remains, and the turn ends with `max_turn_requests`
- **Pinned by** `E2eMaxTurnRequestsTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: The synthesis pass cannot call tools
- **WHEN** the synthesis iteration runs
- **THEN** the request carries no tool definitions
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: The turn end delivers content, then usage, then the response
Every terminal branch SHALL deliver the turn's message content first, then the usage
indicator, then the prompt response, so the indicator never splits the reply.

#### Scenario: End-of-turn ordering
- **WHEN** a turn ends
- **THEN** the last message chunk precedes the `usage_update`, which precedes the prompt response
- **Pinned by** `E2eMaxTurnRequestsTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: Streamed text is not re-emitted
- **WHEN** the reply text was already streamed live by the model delta relay
- **THEN** the turn end emits no second copy of it
- **Pinned by** `PromptRunnerTest` (unit), `E2eWireConformanceTest` (e2e)

### Requirement: The usage indicator reports the model's context window
After a model iteration the agent SHALL report `used` as the prompt token count of the last
completed call and `size` as the selected model's context length. The update SHALL be
omitted when either value is unavailable or not positive.

#### Scenario: Usage after a model call
- **WHEN** the response reports prompt tokens and the model reports a context length
- **THEN** one `usage_update` carries both numbers
- **Pinned by** `E2eWireConformanceTest` (e2e)

#### Scenario: Missing values skip the indicator
- **WHEN** the response reports no usage, or the model's context length is unknown
- **THEN** no `usage_update` is sent
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: A truncated or empty completion gets one continuation pass
A completion cut off at the output-token limit, marked incomplete, or carrying neither text
nor tool calls SHALL be retried exactly once per turn: the partial text is kept, every tool
call of that iteration is dropped without being executed, a synthetic user continuation is
appended, and the loop continues even when the iteration budget is exhausted.

#### Scenario: A truncated iteration is retried
- **WHEN** the first iteration reports `incomplete` with reason `max_output_tokens` and a partial reply
- **THEN** the partial text stays in the history, no tool call of that iteration executes, and the model continues
- **Pinned by** `PromptRunnerTest` (unit)

#### Scenario: A second truncation ends the turn honestly
- **WHEN** the continuation is cut off as well
- **THEN** the turn ends with an interruption note instead of an error response
- **Pinned by** `PromptRunnerTest` (unit)

#### Scenario: An empty completion is retried as empty
- **WHEN** an iteration returns no text and no tool call without a truncation reason
- **THEN** it is retried like a truncation, with a continuation prompt asking to finish or continue
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: A filtered completion ends the turn without retry
A completion reported as filtered SHALL end the turn immediately with a note stating that
the response was blocked by content filtering. It SHALL NOT be retried.

#### Scenario: Content filter stops the turn
- **WHEN** the completion reports `content_filter`
- **THEN** the partial text and a filtering notice are delivered and the turn ends with `end_turn`
- **Pinned by** `PromptRunnerTest` (unit)

#### Scenario: A filtered completion is not retried
- **WHEN** the same prompt would be sent again by a retry
- **THEN** no retry request is made
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: An undocumented completion status is judged by its output
When the completion status is outside the documented set, the iteration SHALL count as a
natural completion if it carries text or complete tool calls; otherwise it SHALL be retried
like an empty completion.

#### Scenario: Usable output wins over an unknown status
- **WHEN** an iteration reports an unknown status but carries tool calls
- **THEN** the tool calls execute and no continuation retry happens
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: Reasoning is replayed within a turn and dropped at its end
Output items of an iteration - reasoning, assistant message and tool calls - SHALL be kept in
production order and replayed to the next iteration of the same turn. They SHALL be dropped
on every turn exit, cancellation included, and SHALL never reach the record.

#### Scenario: The next iteration sees the previous reasoning
- **WHEN** an iteration produced reasoning and tool calls and the calls executed
- **THEN** the next request replays those items verbatim
- **Pinned by** `PromptRunnerTest` (unit), `LlmResponseGoldensTest` (unit)

#### Scenario: A new turn starts from a clean reasoning state
- **WHEN** the previous turn ended, was cancelled or was interrupted
- **THEN** the next request carries no reasoning item
- **Pinned by** `PromptRunnerTest` (unit)

### Requirement: A third raw-identical tool call with an identical result is refused
Within one turn, a tool call whose name and raw argument string are byte-identical to the
immediately preceding executed call SHALL be refused without permission prompt or execution
once that call has already produced the identical model-visible result twice in a row. The
refusal SHALL be a failed result naming the repetition and the raw arguments. A single
identical repeat SHALL execute, a changed result SHALL reset the streak, and the guard SHALL
be cleared at every turn start.

#### Scenario: The third identical call is refused
- **WHEN** the same call with the same result ran twice in a row and is issued a third time
- **THEN** it fails with a result naming the repetition, without executing and without asking for permission
- **Pinned by** `ToolCallExecutorTest` (unit), `PromptRunnerTest` (unit)

#### Scenario: A single repeat still executes
- **WHEN** an identical call follows a different call
- **THEN** it runs normally
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: A changed result re-arms the call
- **WHEN** the identical call produces a different result than before
- **THEN** the streak resets and the next identical call executes
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: Formatting variants are not repeats
- **WHEN** the same parsed arguments are sent with different spacing or key order
- **THEN** the call is not treated as a repetition
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: A new turn resets the guard
- **WHEN** an identical call was refused in the previous turn and is issued in the next turn
- **THEN** it executes
- **Pinned by** `PromptRunnerTest` (unit), `ToolCallExecutorTest` (unit)

### Requirement: Cancellation ends the turn as cancelled and reaches the running tool
`session/cancel` SHALL cancel the running turn by coroutine cancellation, terminate a running
shell command together with its process tree, and answer the prompt with stop reason
`cancelled`.

#### Scenario: A cancelled prompt answers cancelled
- **WHEN** `session/cancel` arrives while a turn is running
- **THEN** the prompt response carries `cancelled`
- **Pinned by** `E2eCancelTest` (e2e)

#### Scenario: A running command is killed
- **WHEN** a cancellation interrupts a running `bash` command
- **THEN** the command's process tree is killed and the cancellation reaches the turn, not a bogus tool failure
- **Pinned by** `BashToolTest` (unit), `FileToolCancelTest` (unit)

#### Scenario: A stuck permission prompt is dismissed
- **WHEN** `$/cancel_request` arrives for a pending `session/request_permission`
- **THEN** the prompt is dismissed and the turn is cancelled rather than answered as a denial
- **Pinned by** `E2eCancelTest` (e2e)

### Requirement: Every streamed chunk of one iteration shares one message id
The reasoning and text chunks of one model iteration SHALL carry the same time-ordered
(UUIDv7) message id, and consecutive iterations SHALL carry distinct ids.

#### Scenario: Chunks group into one client message
- **WHEN** an iteration streams reasoning and then text in several chunks
- **THEN** all chunks share one message id, and the next iteration's chunks carry a different one
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: A mode requested during a turn applies when the turn ends
A mode request that arrives while a prompt turn is running SHALL be recorded (latest wins)
and SHALL NOT govern the running turn. When the turn hands control back, exactly one mode
update SHALL be emitted and the new mode SHALL persist. A cancelled turn SHALL drop the
pending request.

#### Scenario: Mid-turn switch applies at the turn end
- **WHEN** the client requests build mode during a plan-mode turn
- **THEN** the turn keeps plan mode, and one `current_mode_update` follows the turn end
- **Pinned by** `SessionStateDeferredModeTest` (unit), `E2eDeferredModeSwitchTest` (e2e)

#### Scenario: Latest mid-turn request wins
- **WHEN** several mode requests arrive during one turn
- **THEN** only the last one is applied
- **Pinned by** `SessionStateDeferredModeTest` (unit)

#### Scenario: A cancelled turn leaks no mode
- **WHEN** a turn is cancelled while a mode request is pending
- **THEN** the pending mode is dropped and the next turn runs in the previous mode
- **Pinned by** `SessionStateDeferredModeTest` (unit)

#### Scenario: An idle switch applies immediately
- **WHEN** the client requests a mode while no turn runs
- **THEN** the mode applies and is announced immediately
- **Pinned by** `SessionStateDeferredModeTest` (unit)
