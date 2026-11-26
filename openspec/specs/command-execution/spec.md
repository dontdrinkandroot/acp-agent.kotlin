# command-execution Specification

## Purpose

Defines the `bash` tool: how a shell command is started, bounded in time, killed, captured and
reported, and which mode and permission gate apply to it.

## Requirements

### Requirement: The command runs through a shell in the session cwd
`bash` SHALL run its command through `/bin/sh` in the session's working directory as one shell
string, inheriting the agent's environment, and SHALL report the exit code together with the
captured output.

#### Scenario: A command sees the session directory
- **WHEN** `bash` runs a command that prints its working directory
- **THEN** the output is the session cwd
- **Pinned by** `BashToolTest` (unit)

#### Scenario: The exit code is reported
- **WHEN** a command exits non-zero
- **THEN** the result reports the failure and the exit code
- **Pinned by** `BashToolTest` (unit)

### Requirement: A command is killed with its whole process tree after the timeout
A command SHALL be terminated when it exceeds the configured timeout (`tools.bash.timeoutSeconds`,
1 to 3600 seconds), killing the entire process tree rather than only the shell.

#### Scenario: A hanging command is stopped
- **WHEN** a command runs longer than the configured timeout
- **THEN** it and its children are killed and the tool reports a timeout failure
- **Pinned by** `BashToolTest` (unit)

#### Scenario: A child of the shell is killed too
- **WHEN** the shell spawned a long-running child
- **THEN** the child is terminated as well
- **Pinned by** `BashToolTest` (unit)

### Requirement: Cancellation terminates the running command
A cancelled turn SHALL kill the running command's process tree and abort the tool call, so no
orphan process survives a `session/cancel`.

#### Scenario: Cancel mid-command
- **WHEN** the turn is cancelled while a command runs
- **THEN** the command's tree is killed and the cancellation propagates instead of a bogus failure
- **Pinned by** `BashToolTest` (unit), `RunToolTest` (unit)

### Requirement: Output is captured progressively with bounded tails
The tool SHALL keep at most 30k characters of stdout and of stderr each, dropping the
beginning and prefixing a marker that states how many characters were omitted. Output SHALL be
captured as it arrives, so a command whose backgrounded child keeps the pipe open still reports
what it produced before the tree was force-killed.

#### Scenario: Long output keeps its tail
- **WHEN** a command prints more than 30k characters
- **THEN** the result holds the last 30k characters preceded by an omission marker naming the dropped count
- **Pinned by** `BashToolTest` (unit)

#### Scenario: Streams stay separate
- **WHEN** a command writes to stdout and stderr
- **THEN** both are reported separately, each with its own bound
- **Pinned by** `BashToolTest` (unit)

#### Scenario: A backgrounded child does not lose the output
- **WHEN** a command exits but a backgrounded child keeps the output pipe open
- **THEN** the tool force-kills the tree after the drain grace and reports the output captured so far
- **Pinned by** `BashToolTest` (unit)

### Requirement: bash exists only in bash mode and always asks
The `bash` tool SHALL be offered only in bash mode, SHALL be classified as mutating, and
SHALL require a permission decision for every call that no permanent grant covers.

#### Scenario: Not offered in plan or build mode
- **WHEN** the session runs in plan or build mode
- **THEN** `bash` is not among the offered tools and a call to it fails as disabled in the current mode
- **Pinned by** `ToolRegistryTest` (unit), `E2eModeRestrictionTest` (e2e)

#### Scenario: A call asks for permission
- **WHEN** the model calls `bash` in bash mode without a grant
- **THEN** a permission request is sent before the command runs
- **Pinned by** `E2eFileStoreTest` (e2e), `ToolCallExecutorTest` (unit)
