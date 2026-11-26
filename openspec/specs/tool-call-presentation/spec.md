# tool-call-presentation Specification

## Purpose

Defines how a tool call is presented to the client: the announcement, its title, the file
locations it carries, the completion update and its content blocks, and the diff payloads that
show file changes.

## Requirements

### Requirement: A tool call is announced before it runs
Before a call executes, the agent SHALL emit one `tool_call` update carrying a title, the tool
kind, the in-progress status, the tool's file locations and the parsed input arguments. Path
locations SHALL also accompany the permission request and the load replay, so the client can
follow along.

#### Scenario: The announcement carries title, kind and locations
- **WHEN** a path-scoped tool call starts
- **THEN** the `tool_call` update carries the title, the kind and the target as a location
- **Pinned by** `E2eWireConformanceTest` (e2e), `ToolCallExecutorTest` (unit)

#### Scenario: The permission request carries the same locations
- **WHEN** a call asks for permission
- **THEN** the permission request's tool call carries the locations
- **Pinned by** `E2eFileStoreTest` (e2e)

### Requirement: A call's title names what it acts on
The title SHALL identify the call's subject for clients that render only the title: tools with
meaningful arguments SHALL override it with a `name(key: value, ...)` summary; `run` and the
run-config tools SHALL lead with the configuration name; and the move and delete tools SHALL
name their path. The path tools that only prompt for out-of-project access SHALL keep the bare
name. Each value SHALL be capped at 100 characters and newlines SHALL be flattened.

#### Scenario: run leads with the config name
- **WHEN** `run` is called with a config name and arguments
- **THEN** the title starts with `run(<config>` and the config name is never truncated away
- **Pinned by** `FormatToolTitleTest` (unit), `RunToolTest` (unit)

#### Scenario: A long argument is truncated, the name is not
- **WHEN** a title's argument value is longer than 100 characters
- **THEN** that value is ellipsized but the tool and config names stay intact
- **Pinned by** `FormatToolTitleTest` (unit)

#### Scenario: Embedded newlines are flattened
- **WHEN** an argument carries a multi-line value
- **THEN** the title stays on one line
- **Pinned by** `FormatToolTitleTest` (unit)

#### Scenario: A deletion names its target
- **WHEN** `delete_file` is called
- **THEN** the title carries the path being deleted
- **Pinned by** `DeleteFileToolTest` (unit), `FormatToolTitleTest` (unit)

### Requirement: The programmatic tool name member is not sent
The `tool_call` update SHALL NOT carry the optional programmatic tool name: the v1 protocol
model this agent implements has no such member, so emitting one would be outside the contract.
The title remains the identification surface. (Issue #64 tracks the gap; the v2 model carries
the member, so a future v2 migration revisits this rule.)

#### Scenario: The name member is absent
- **WHEN** a tool call is announced
- **THEN** the raw `session/update` line's tool call object carries no name member
- **Pinned by** `E2eWireConformanceTest` (e2e, raw-wire scan)

### Requirement: Completion reports status and the result text
A completed call SHALL emit one `tool_call_update` with status `completed` and the result text
as content; a failed call SHALL emit status `failed` with the error text. A denied call SHALL
report the denial reason as its content. Every such update SHALL also carry the plain result as
`rawOutput`.

#### Scenario: A success carries its output
- **WHEN** a tool completes successfully
- **THEN** the update carries `completed` and the result text as content and as `rawOutput`
- **Pinned by** `E2eWireConformanceTest` (e2e), `ToolCallExecutorTest` (unit)

#### Scenario: A failure carries its error
- **WHEN** a tool returns an error
- **THEN** the update carries `failed` and the error text
- **Pinned by** `ToolCallExecutorTest` (unit), `BashToolTest` (unit)

#### Scenario: A denial carries its reason
- **WHEN** the user denies a call
- **THEN** the update carries `failed` and the denial text
- **Pinned by** `ToolCallExecutorTest` (unit), `E2eFileStoreTest` (e2e)

### Requirement: A file change is shown as a whole-file diff
A completed file mutation SHALL carry its change as a `diff` content block describing the
whole file - the complete old and new text, with no old text only for a genuinely new file.

#### Scenario: An edit shows both sides
- **WHEN** `edit_file` completes locally
- **THEN** the diff carries the complete old and new content of the file
- **Pinned by** `EditFileToolTest` (unit), `E2eWireConformanceTest` (e2e)

#### Scenario: A new file has no old side
- **WHEN** `write_file` creates a file that did not exist
- **THEN** the diff's old text is absent
- **Pinned by** `WriteFileToolTest` (unit)

#### Scenario: A deletion reports the removed content
- **WHEN** `delete_file` or `delete_directory` removes files
- **THEN** each is reported with empty new text and its old content
- **Pinned by** `DeleteFileToolTest` (unit), `E2eWireConformanceTest` (e2e)

### Requirement: Only proxy-rendered changes suppress the diff
A write or edit performed through the client's filesystem proxy SHALL NOT carry a diff, because
the client renders that change itself. Every other mutation SHALL carry its diffs, including a
write through the local store.

#### Scenario: Proxy write carries no diff
- **WHEN** `write_file` goes through the client proxy
- **THEN** the update carries the result text and no diff block
- **Pinned by** `WriteFileToolTest` (unit), `EditFileToolTest` (unit)

#### Scenario: A local delete still carries its diff
- **WHEN** the client offers the proxy and `delete_file` runs locally
- **THEN** the diff is sent, because the proxy renders nothing for a delete
- **Pinned by** `DeleteFileToolTest` (unit)

### Requirement: A dropped diff is never silent
A diff that cannot be sent SHALL be surfaced as a `(diff omitted: <reason>)` note in the result
text and as a warning on stderr. A partial tree report SHALL state
`(original content reported for M of N files)`.

#### Scenario: Size cap
- **WHEN** one side of a diff exceeds 100k characters
- **THEN** the diff is omitted and the note names the side, its size and the limit
- **Pinned by** `WriteFileToolTest` (unit), `EditFileToolTest` (unit)

#### Scenario: Unreadable pre-read
- **WHEN** the old content of an existing file cannot be read
- **THEN** the diff is omitted with a note instead of claiming the file is new
- **Pinned by** `WriteFileToolTest` (unit)

#### Scenario: A partial tree report states the gap
- **WHEN** only some files of a deleted directory could be reported
- **THEN** the result states how many of how many were reported
- **Pinned by** `DeleteDirectoryToolTest` (unit)

### Requirement: Diff payloads are bounded per call
One call SHALL carry at most 50 file diffs and at most one MiB of aggregate diff content; the
per-file and per-call caps SHALL compose, and the reported prefix SHALL be deterministic.

#### Scenario: The file count is capped
- **WHEN** a tree mutation changes more than 50 files
- **THEN** at most 50 diffs are sent, in sorted path order, and the note states the gap
- **Pinned by** `DeleteDirectoryToolTest` (unit), `MoveDirectoryToolTest` (unit)

#### Scenario: The aggregate budget is capped
- **WHEN** the diffs of one call would exceed one MiB together
- **THEN** the payload stops at the budget and the note states the gap
- **Pinned by** `MoveDirectoryToolTest` (unit), `DeleteDirectoryToolTest` (unit)

#### Scenario: Each dropped diff is visible on the wire result
- **WHEN** a diff is dropped by any cap
- **THEN** the result text names the reason
- **Pinned by** `WriteFileToolTest` (unit), `DeleteDirectoryToolTest` (unit)
