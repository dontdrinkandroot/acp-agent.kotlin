# session-lifecycle Specification

## Purpose

Lets an IDE open, list, restore and end conversations with the agent, and keeps each
conversation's state durable across agent and IDE restarts.

## Requirements

### Requirement: A new session has a minted id and is tracked as open
`session/new` SHALL answer with a session id of the form `sess_` + 16 lowercase hex digits,
and the session SHALL be tracked as open in the running process until it ends.

#### Scenario: New session id shape
- **WHEN** the client sends `session/new` with a usable cwd
- **THEN** the response carries a session id matching `sess_[0-9a-f]{16}`
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A new session appears as open in the session list
- **WHEN** a session was created in this process and `session/list` is called for its cwd
- **THEN** its entry carries `_meta: {"net.dontdrinkandroot/active": true}`
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

### Requirement: The session cwd must be a usable directory
`session/new`, `session/load` and `session/resume` SHALL refuse a cwd that is not an
absolute path to an existing directory with the invalid-params error (`-32602`), before a
session exists or is restored. The cwd SHALL be kept verbatim - no canonicalization, no
symlink folding - because it is the session's identity.

#### Scenario: Missing directory is refused
- **WHEN** `session/new` names a cwd that does not exist, is a regular file, or is relative
- **THEN** the request fails with `-32602` and no session record is written
- **Pinned by** `SessionCwdTest` (unit), `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: An unparseable path is a clean refusal
- **WHEN** the cwd string cannot be parsed as a path at all
- **THEN** the request fails with `-32602` naming the cwd, not with an internal error
- **Pinned by** `SessionCwdTest` (unit)

#### Scenario: Restore checks the cwd after the record lookup
- **WHEN** `session/load` names an unknown session id and an unusable cwd
- **THEN** the refusal is the unknown-session one, because the record lookup runs first
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Two spellings of one directory are not folded
- **WHEN** a session is loaded with a cwd that resolves to the same directory through a symlink but differs textually
- **THEN** the load is refused as a cwd mismatch
- **Pinned by** `SessionLifecycleTest` (unit)

### Requirement: An ended session refuses further work
After `session/delete` or `session/close`, further `session/prompt`, `session/set_mode`,
`session/set_model` and `session/set_config_option` requests SHALL be refused with `-32002`
(resource not found). While the process still tracks the session, `session/close` and
`session/delete` SHALL stay callable and idempotent; for an id it no longer tracks, the ACP
layer answers `-32602`.

#### Scenario: Prompt after delete is refused
- **WHEN** a client deletes an active session and then sends `session/prompt` to it
- **THEN** the request fails with `-32002`, reaches no model and writes no record
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: Closing twice succeeds
- **WHEN** `session/close` is sent for a session the process still tracks but already closed
- **THEN** the request succeeds without side effects
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Close of a session the process no longer tracks
- **WHEN** `session/close` names a session id this process does not track
- **THEN** the answer is `-32602` from the ACP layer, which is why idempotence is scoped to tracked sessions
- **Pinned by** none - SDK behavior, pinned upstream

### Requirement: The session record holds the conversation's durable state
One JSON file per session SHALL hold the conversation history in the Responses wire dialect,
the mode, the title, the model, the reasoning effort, the provider selection, the plan and
the terminal outcome of every tool call. Reasoning items SHALL NOT be persisted.

#### Scenario: Record round-trip
- **WHEN** a turn that used a tool completes and the record is read back
- **THEN** history, mode, title, model, reasoning, provider, plan and tool outcomes are restored
- **Pinned by** `SessionStoreTest` (unit), `SessionStateProviderSelectionTest` (unit)

#### Scenario: Reasoning never reaches disk
- **WHEN** a turn streamed reasoning items and the record is written
- **THEN** the record's history holds no reasoning item
- **Pinned by** `SessionStateRepairTest` (unit)

#### Scenario: Records from an older dialect still decode
- **WHEN** a record without the provider or tool-outcome fields is loaded
- **THEN** it decodes and replays, with absent outcomes treated as completed
- **Pinned by** `SessionStoreTest` (unit), `SessionStateProviderSelectionTest` (unit)

### Requirement: The record is persisted at every durable point
The agent SHALL persist the session after every completed turn, after every mode, model,
reasoning or provider change, after every executed or denied tool call, when a turn is
interrupted, on `session/close` and at shutdown. A failed write SHALL be logged and SHALL
NOT fail the turn or the request.

#### Scenario: An interrupted turn is persisted
- **WHEN** `session/cancel` cancels a running turn, or the client closes stdin mid-turn
- **THEN** the record holds the user message, the partial reply and every applied tool call
- **Pinned by** `SessionLifecycleTest` (unit), `E2eShutdownTest` (e2e)

#### Scenario: Tool effects survive a crash mid-turn
- **WHEN** the agent dies after a tool call was executed but before the turn ended
- **THEN** the last persisted record already contains that tool call's result
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: A delete is never resurrected by an in-flight write
- **WHEN** `session/delete` arrives while a persist is running
- **THEN** the record stays deleted afterwards
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Persistence failures do not fail the turn
- **WHEN** the record cannot be written
- **THEN** the turn completes and the failure is reported on stderr only
- **Pinned by** `SessionLifecycleTest` (unit)

### Requirement: Record writes are atomic and sweep crash orphans
A record SHALL be written to a temporary file and moved over the target. Temp files
matching the session temp naming and older than one hour SHALL be swept during `save` and
`list`, at most once per hour; fresh temp files, symlinks and other names SHALL be left
untouched.

#### Scenario: A crashed write leaves no permanent debris
- **WHEN** a `session-*.tmp` file older than one hour is present in the sessions directory and a save runs
- **THEN** the stale temp file is removed and the record is written normally
- **Pinned by** `AtomicWriteTest` (unit), `SessionStoreTest` (unit)

#### Scenario: A concurrent writer's fresh temp survives
- **WHEN** a sweep runs while another process's temp file was just created
- **THEN** that temp file is not removed
- **Pinned by** `SessionStoreTest` (unit)

#### Scenario: The sweep is throttled
- **WHEN** many saves run within one hour
- **THEN** the directory is scanned once, not once per save
- **Pinned by** `SessionStoreTest` (unit)

### Requirement: Client-supplied session ids are validated before any file access
`session/load`, `session/resume` and `session/delete` SHALL reject an id that is not
`sess_` + 16 lowercase hex digits with the invalid-params error before the id reaches the
filesystem.

#### Scenario: A traversal-shaped id is refused
- **WHEN** `session/load` names an id containing `..`, `/` or an otherwise invalid shape
- **THEN** the request fails with `-32602` and no file is read
- **Pinned by** `SessionLifecycleTest` (unit), `AgentSupportImplTest` (unit)

#### Scenario: Deleting an unknown id succeeds
- **WHEN** `session/delete` names a well-formed id with no record
- **THEN** the request succeeds
- **Pinned by** `AgentSupportImplTest` (unit)

### Requirement: Load replays the conversation before the response, resume does not
`session/load` SHALL deliver the replayed conversation to the client before the load
response is dispatched. `session/resume` SHALL restore the session without replaying.

#### Scenario: Updates arrive before the load returns
- **WHEN** a persisted session with a conversation is loaded
- **THEN** every replay update has already been delivered when the load response arrives
- **Pinned by** `E2eSessionPersistenceTest` (e2e)

#### Scenario: Resume is silent
- **WHEN** the same session is resumed
- **THEN** no conversation update is delivered
- **Pinned by** `E2eSessionPersistenceTest` (e2e)

### Requirement: Replay reproduces the conversation and its real outcomes
Replay SHALL render user and agent text as message chunks, each assistant tool call as a
pending `tool_call`, each tool result as a terminal `tool_call_update` carrying the recorded
outcome and text, and the plan as its update. Denied, disabled, unknown and failed calls
SHALL replay as failed; successful ones as completed.

#### Scenario: A denied call replays as failed
- **WHEN** a record holds a denied out-of-project read and an unknown tool call
- **THEN** both replay as `failed` with their denial text
- **Pinned by** `ReplayHistoryTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A successful plan call replays as completed
- **WHEN** a record holds a completed `update_plan` call
- **THEN** it replays as `completed` and the plan update follows
- **Pinned by** `E2eSessionPersistenceTest` (e2e)

#### Scenario: An unknown recorded outcome falls back to completed
- **WHEN** a record's outcome value is not `completed` or `failed`
- **THEN** the call replays as completed
- **Pinned by** `ReplayHistoryTest` (unit)

#### Scenario: A captured item replays like its typed counterpart
- **WHEN** a history item was kept verbatim because it carries content the typed model does not cover
- **THEN** it replays exactly like the typed equivalent of its meaning
- **Pinned by** `ReplayHistoryTest` (unit)

### Requirement: Restore refusals are distinct
`session/load` and `session/resume` SHALL answer `-32602` for an unknown id, a cwd that
differs from the record's, and an id that is already active, and SHALL answer an internal
error for an unreadable or corrupt record. A refusal SHALL leave the record in place.

#### Scenario: Unknown id
- **WHEN** the client loads an id with no record
- **THEN** the answer is `-32602` naming the id
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Cwd mismatch
- **WHEN** the client loads a record with a different cwd than the record's
- **THEN** the answer is `-32602` naming both directories
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Double load
- **WHEN** the client loads a session that is already open in this process
- **THEN** the answer is `-32602` ("already active")
- **Pinned by** `SessionLifecycleTest` (unit)

#### Scenario: Corrupt record
- **WHEN** the record file is not valid
- **THEN** the answer is an internal error naming the load failure
- **Pinned by** `AgentSupportImplTest` (unit)

### Requirement: The session list filters, sorts and formats
`session/list` SHALL return the records whose cwd equals the requested cwd (all records when
no cwd is requested), most recently updated first, skipping unreadable records. `updatedAt`
SHALL be an RFC 3339 wall-clock timestamp with a numeric offset and always include seconds
and milliseconds.

#### Scenario: Filtering by cwd
- **WHEN** `session/list` is called with a cwd
- **THEN** only records of that cwd are returned
- **Pinned by** `AgentSupportImplTest` (unit)

#### Scenario: Recency ordering
- **WHEN** several records exist
- **THEN** they are ordered by update time, newest first
- **Pinned by** `AgentSupportImplTest` (unit)

#### Scenario: Timestamp shape
- **WHEN** the client reads `updatedAt`
- **THEN** it parses as an offset date-time whose instant equals the record's update time, with a numeric offset and no `Z`
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: Corrupt records are skipped
- **WHEN** one record file is unreadable
- **THEN** the list returns the other records and logs the skip
- **Pinned by** `AgentSupportImplTest` (unit)

### Requirement: The open-session marker tracks liveness only
Only sessions open in the running process SHALL carry the active `_meta` marker. Closing,
deleting or a failed load replay SHALL remove the marker and free the id for a later load.

#### Scenario: Close unmarks and frees the id
- **WHEN** one of two open sessions is closed
- **THEN** its entry loses the marker and the session can be loaded again
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A failed replay releases the session
- **WHEN** the load replay fails
- **THEN** the session is released and its resources are closed
- **Pinned by** `SessionLifecycleTest` (unit)

### Requirement: The title is derived once and announced once
The title SHALL be derived from the first user message, trimmed and capped at 72 code
points (with a trailing ellipsis when cut). It SHALL be announced to the client exactly once
as a `session_info_update`, inside the prompt's own update stream.

#### Scenario: First prompt announces the title
- **WHEN** a fresh session's first prompt is sent
- **THEN** exactly one `session_info_update` with the derived title is delivered before the prompt response
- **Pinned by** `SessionStateTitleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A restored title is not re-announced
- **WHEN** a session with a title is loaded and prompted again
- **THEN** no `session_info_update` is sent
- **Pinned by** `SessionStateTitleTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A record without a title announces at the next prompt
- **WHEN** a record with an empty title is restored and prompted
- **THEN** the derived title is announced then
- **Pinned by** `SessionStateTitleTest` (unit)

#### Scenario: An empty first message still titles the session
- **WHEN** the first user message has no text
- **THEN** the title is `(empty message)`
- **Pinned by** `SessionStateTitleTest` (unit)

### Requirement: Every assistant tool call is paired with a tool result
No history consumer - the model request, the load replay or the persisted record - SHALL
observe an assistant tool call without a matching result. A call left unanswered by an
interrupted turn SHALL be closed by a synthetic failed result stating that it was interrupted
and that effects may already be applied.

#### Scenario: A cancelled turn's dangling call is closed
- **WHEN** a turn is cancelled after a tool call but before its result
- **THEN** the next request, replay and record carry a synthetic failed result for that call
- **Pinned by** `ToolCallPairingRepairTest` (unit), `SessionStateRepairTest` (unit)

#### Scenario: A batch keeps its real results
- **WHEN** some calls of one assistant batch completed and one never answered
- **THEN** only the unanswered call is repaired, placed after the batch's present results
- **Pinned by** `ToolCallPairingRepairTest` (unit)

#### Scenario: A pair-complete history is unchanged
- **WHEN** every call already has a result
- **THEN** the repair is a no-op and is idempotent
- **Pinned by** `ToolCallPairingRepairTest` (unit)

#### Scenario: The stored history is not mutated
- **WHEN** the repaired view is read
- **THEN** the in-memory history still holds the unrepaired items
- **Pinned by** `SessionStateRepairTest` (unit)

### Requirement: Delete removes the record and closes the session's resources
`session/delete` SHALL close the session's MCP connections and model client, remove the
record, and drop the session from the process's live set.

#### Scenario: Delete closes resources
- **WHEN** the client deletes an active session
- **THEN** its record is gone and its resources are closed
- **Pinned by** `SessionLifecycleTest` (unit), `E2eSessionPersistenceTest` (e2e)
