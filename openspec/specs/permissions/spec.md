# permissions Specification

## Purpose

Defines when a tool call may run without asking, when the agent asks the user, and when it
refuses outright - the path tiers, the permission prompt and its options, the permanent
decisions, the scratch directory and the mode gate.

## Requirements

### Requirement: The mode gate runs before permission and execution
A tool that is registered but not offered in the governing mode SHALL fail with a
"disabled in current mode" result and SHALL NOT reach the permission stage or execute. This
covers a tool call carried over from an earlier mode switch, and keeps plan mode read-only.

#### Scenario: A build tool called in plan mode is refused
- **WHEN** the model calls a file-mutating tool while the session runs in plan mode
- **THEN** the call fails without a permission prompt and without touching the filesystem
- **Pinned by** `E2eModeRestrictionTest` (e2e), `ToolCallExecutorTest` (unit)

#### Scenario: A bash call in build mode is refused
- **WHEN** the model calls `bash` while the session runs in build mode
- **THEN** the call fails as disabled in the current mode
- **Pinned by** `E2eModeRestrictionTest` (e2e), `ToolRegistryTest` (unit)

### Requirement: Tools without a path use their kind to decide
A call without filesystem targets SHALL run without asking when the tool is non-mutating, and
SHALL ask when it is mutating.

#### Scenario: A mutating non-path tool asks
- **WHEN** `bash` is called in bash mode
- **THEN** a permission request is sent
- **Pinned by** `ToolCallExecutorTest` (unit), `E2eFileStoreTest` (e2e)

#### Scenario: A read-only non-path tool runs
- **WHEN** `calc`, `web_fetch` or `get_current_mode` is called
- **THEN** it runs without a permission request
- **Pinned by** `E2eWebFetchTest` (e2e), `ToolCallExecutorTest` (unit)

### Requirement: Every target must lie inside the cwd to run without asking
A path-scoped call SHALL run without asking when every target resolves - symlinks followed -
inside the session working directory. A call with one target outside SHALL fall through to
the tier check as a whole.

#### Scenario: In-project read
- **WHEN** `read_file` is called for a path inside the session cwd
- **THEN** it runs without a permission request
- **Pinned by** `PermissionAndFileStoreTest` (unit), `E2eFileStoreTest` (e2e)

#### Scenario: A symlink escape still gates
- **WHEN** an in-project path is a symlink pointing outside the cwd
- **THEN** the call is not treated as in-project
- **Pinned by** `ContainmentTest` (unit), `PermissionAndFileStoreTest` (unit)

#### Scenario: A partially covered call asks
- **WHEN** a move has its source inside a trusted tier and its destination outside every tier
- **THEN** the call asks for permission
- **Pinned by** `ToolTargetTest` (unit), `PermissionAndFileStoreTest` (unit)

### Requirement: The readable and writable tiers grant prompt-free access
The read-only path tools (`read_file`, `list_dir`, `glob`, `grep`) SHALL run without asking
when all targets lie inside the readable or writable tier. The mutating path tools SHALL run
without asking when all targets lie inside the writable tier.

#### Scenario: A read under the readable tier
- **WHEN** `grep` is called for a path under a configured readable directory
- **THEN** it runs without a permission request
- **Pinned by** `E2eDirectoryTiersTest` (e2e), `PermissionAndFileStoreTest` (unit)

#### Scenario: A write under the readable tier still asks
- **WHEN** `write_file` targets a path that is only readable
- **THEN** a permission request is sent
- **Pinned by** `E2eDirectoryTiersTest` (e2e), `PermissionAndFileStoreTest` (unit)

#### Scenario: Siblings are not covered
- **WHEN** the target is a sibling of a configured tier, not inside it
- **THEN** the call asks
- **Pinned by** `PermissionAndFileStoreTest` (unit)

### Requirement: A path outside every tier follows the configured policy
With `permissions.unlistedPaths = prompt` the agent SHALL ask. With `deny` it SHALL refuse
without a prompt, naming the tool, the targets and the configuration key that would cover
them, and the call SHALL count as failed.

#### Scenario: Prompt
- **WHEN** a read targets a path outside the cwd and every tier
- **THEN** a permission request carries the path so the user can judge it
- **Pinned by** `E2eFileStoreTest` (e2e), `PermissionAndFileStoreTest` (unit)

#### Scenario: Deny names the fix
- **WHEN** `unlistedPaths` is `deny` and the target is unlisted
- **THEN** the call fails with a message naming `permissions.readableDirectories` or `permissions.writableDirectories`
- **Pinned by** `PermissionAndFileStoreTest` (unit), `E2eDirectoryTiersTest` (e2e)

### Requirement: The permission prompt offers the four standard options
A permission request SHALL offer allow-once, allow-always, reject-once and reject-always,
each with a distinct id and a non-blank name, and the decision SHALL follow the option's kind,
never its id spelling.

#### Scenario: All four options are offered
- **WHEN** the agent asks for permission
- **THEN** the request carries the four option kinds with distinct ids and names
- **Pinned by** `E2eFileStoreTest` (e2e), `ToolCallExecutorTest` (unit)

#### Scenario: An unknown option id is a denial
- **WHEN** the client answers with an id the agent did not offer
- **THEN** the call is treated as denied and the mismatch is logged
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: A failing permission channel is a denial
- **WHEN** the permission request itself fails
- **THEN** the call is denied rather than allowed, and the cause is logged
- **Pinned by** `ToolCallExecutorTest` (unit)

### Requirement: Permanent decisions are per tool and per session
`allow_always` and `reject_always` SHALL apply to every later call of the same tool in the
session without asking again. `allow_once` and `reject_once` SHALL apply to that call only.
The decisions SHALL NOT be persisted.

#### Scenario: Allow-always covers later calls
- **WHEN** the user allows a tool always and the tool is called again
- **THEN** the second call does not ask
- **Pinned by** `ToolCallExecutorTest` (unit), `E2eFileStoreTest` (e2e)

#### Scenario: Reject-always covers later calls
- **WHEN** the user rejects a tool always and the tool is called again
- **THEN** the second call is denied without asking
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: Allow-once does not persist
- **WHEN** the user allows once and the tool is called again
- **THEN** the second call asks again
- **Pinned by** `ToolCallExecutorTest` (unit)

#### Scenario: Grants do not survive a restart
- **WHEN** a session with a permanent grant is restored
- **THEN** the tool asks again
- **Pinned by** `ToolCallExecutorTest` (unit)

### Requirement: MCP tools ask unless a trusted annotation says they are read-only
An MCP tool SHALL ask for permission by default. It SHALL run without asking only when the
server annotates it `readOnlyHint: true` and annotations are trusted.

#### Scenario: An unannotated tool asks
- **WHEN** a server offers a tool without annotations
- **THEN** every call asks
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `McpBridgeTest` (unit)

#### Scenario: A trusted read-only tool runs
- **WHEN** the server annotates the tool `readOnlyHint: true` and annotations are trusted
- **THEN** the call runs without a request
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `McpBridgeTest` (unit)

#### Scenario: Distrust ignores the annotation
- **WHEN** `mcp.trustAnnotations` is false
- **THEN** the tool asks even when annotated read-only
- **Pinned by** `McpBridgeTest` (unit)

### Requirement: The agent's scratch directory is read-trusted
The configured scratch directory SHALL be prepared at session assembly - created `0700` when
missing, verified when present - and SHALL be merged into the readable tier, so reads there
run without asking while writes still ask. Preparation SHALL fail session creation when the
path is a symlink, not a directory, foreign-owned, or not on a POSIX filesystem.

#### Scenario: Read prompt-free, write prompts
- **WHEN** a read targets the scratch directory and a write targets it too
- **THEN** the read runs without asking and the write asks
- **Pinned by** `ScratchDirectoryTest` (unit), `PermissionAndFileStoreTest` (unit)

#### Scenario: A symlinked scratch directory fails the session
- **WHEN** the scratch path is a symlink
- **THEN** `session/new` fails
- **Pinned by** `E2eFileStoreTest` (e2e), `ScratchDirectoryTest` (unit)

#### Scenario: A too-open directory is repaired
- **WHEN** the scratch directory exists with wider permissions and is owned by the agent's user
- **THEN** it is restricted to `0700`
- **Pinned by** `ScratchDirectoryTest` (unit)

#### Scenario: A foreign-owned directory is refused
- **WHEN** the scratch directory belongs to another user
- **THEN** session creation fails
- **Pinned by** `ScratchDirectoryTest` (unit)

### Requirement: Cancellation of a permission prompt cancels the turn
`$/cancel_request` for a pending permission request SHALL dismiss the prompt and cancel the
turn via cancellation, not answer it as a denial.

#### Scenario: A stuck prompt is dismissed
- **WHEN** `$/cancel_request` arrives while a permission request is pending
- **THEN** the prompt is dismissed and the turn ends cancelled
- **Pinned by** `E2eCancelTest` (e2e)
