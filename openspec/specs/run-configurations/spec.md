# run-configurations Specification

## Purpose

Defines the project's durable run configurations and the two ways the agent uses them: the
prompt-free `run` tool that executes one, and the dedicated tools that create, update and
delete entries in the project's configuration file.

## Requirements

### Requirement: Run configurations live in a project file
Run configurations SHALL be read from `<cwd>/.ai/run.json`, a JSON object mapping a name to an
entry with a command and an optional description. A missing or unparseable file SHALL be
treated as no configurations (logged), so a broken file never fails a turn.

#### Scenario: Configurations are read from disk every access
- **WHEN** the file changes between two calls
- **THEN** the next call sees the new entries
- **Pinned by** `E2eRunToolTest` (e2e), `RunToolTest` (unit)

#### Scenario: A corrupt file reads as empty
- **WHEN** the file does not parse
- **THEN** the `run` tool reports an unknown configuration and the write tools refuse to touch the file
- **Pinned by** `RunConfigToolsTest` (unit)

### Requirement: run executes a configuration prompt-free in every mode
The `run` tool SHALL be available in plan, build and bash mode, SHALL be non-mutating and
SHALL never ask for permission. It SHALL execute the named configuration's command in the
session working directory and reject an unknown configuration with a result listing the
available names.

#### Scenario: Available in plan mode
- **WHEN** the session runs in plan mode
- **THEN** `run` executes a configuration without a permission request
- **Pinned by** `E2eRunToolTest` (e2e)

#### Scenario: Unknown configuration
- **WHEN** the model names a configuration that does not exist
- **THEN** the call fails listing the available configuration names
- **Pinned by** `RunToolTest` (unit)

### Requirement: Arguments reach the command as positional parameters
The model's `args` array SHALL be delivered as positional parameters at the command's `[args]`
slot, one array element becoming one argv token that is never shell-interpreted. A
configuration without the slot SHALL reject a non-empty argument array; an empty array SHALL be
accepted.

#### Scenario: An element with whitespace stays one token
- **WHEN** `args` contains an element with spaces
- **THEN** the command receives it as a single argument
- **Pinned by** `RunToolTest` (unit), `E2eRunToolTest` (e2e)

#### Scenario: No slot, no arguments
- **WHEN** the configuration's command has no `[args]` slot and arguments are passed
- **THEN** the call fails naming the configuration
- **Pinned by** `RunToolTest` (unit)

#### Scenario: Shell metacharacters are not interpreted
- **WHEN** an argument contains shell syntax such as `;`, `$` or backticks
- **THEN** it reaches the command verbatim as one argv token
- **Pinned by** `RunToolTest` (unit)

### Requirement: Run-configuration writes need build or bash mode
`create_run_config`, `update_run_config` and `delete_run_config` SHALL be offered only in
build and bash mode, SHALL be mutating (so they ask for permission), and SHALL write the file
atomically.

#### Scenario: Absent in plan mode
- **WHEN** the session runs in plan mode
- **THEN** the write tools are not offered and a call fails as disabled in the current mode
- **Pinned by** `E2eRunConfigCrudTest` (e2e), `ToolRegistryTest` (unit)

#### Scenario: A write asks for permission
- **WHEN** the model creates a configuration in build mode
- **THEN** a permission request precedes the write
- **Pinned by** `E2eRunConfigCrudTest` (e2e)

#### Scenario: Writes are atomic
- **WHEN** a write is performed
- **THEN** the file is replaced atomically, never left half-written
- **Pinned by** `RunConfigToolsTest` (unit), `AtomicWriteTest` (unit)

### Requirement: The create tool validates name and command
`create_run_config` SHALL reject a blank name with a clean error result, reject a blank
command, and reject a name that already exists.

#### Scenario: A blank name is refused
- **WHEN** the name is blank
- **THEN** the call fails with "Run configuration name must not be blank"
- **Pinned by** `RunConfigToolsTest` (unit)

#### Scenario: An existing name is refused
- **WHEN** the name is already defined
- **THEN** the call fails and the file is unchanged
- **Pinned by** `RunConfigToolsTest` (unit)

#### Scenario: A blank command is refused
- **WHEN** the command is blank
- **THEN** the call fails and the file is unchanged
- **Pinned by** `RunConfigToolsTest` (unit)

### Requirement: The update tool is field-level
`update_run_config` SHALL change only the fields that are provided: an omitted field keeps
its value, a blank command is rejected (never cleared), and an empty description clears the
description. Unknown names SHALL be refused.

#### Scenario: Omitted fields are kept
- **WHEN** only the command is updated
- **THEN** the description is unchanged
- **Pinned by** `RunConfigToolsTest` (unit)

#### Scenario: A blank command is rejected
- **WHEN** the update sends a blank command
- **THEN** the call fails and the previous command stays
- **Pinned by** `RunConfigToolsTest` (unit)

#### Scenario: Empty description clears
- **WHEN** the update sends an empty description
- **THEN** the entry ends up without a description
- **Pinned by** `RunConfigToolsTest` (unit)

#### Scenario: Unknown name
- **WHEN** the update names a configuration that does not exist
- **THEN** the call fails
- **Pinned by** `RunConfigToolsTest` (unit)

### Requirement: The delete tool removes one entry
`delete_run_config` SHALL remove exactly the named entry, SHALL refuse an unknown name, and
SHALL leave the other entries untouched.

#### Scenario: Delete keeps the rest
- **WHEN** one of several configurations is deleted
- **THEN** only that entry is gone
- **Pinned by** `RunConfigToolsTest` (unit), `E2eRunConfigCrudTest` (e2e)

#### Scenario: Unknown name
- **WHEN** the delete names a configuration that does not exist
- **THEN** the call fails
- **Pinned by** `RunConfigToolsTest` (unit)

### Requirement: The generic file tools cannot write the configuration file
The configuration file SHALL be refused by `read_file`, `edit_file`, `write_file` and
`delete_file` and hidden from listings and searches, so a rejected change cannot be re-applied
without the dedicated tools' permission prompt. Unknown entry fields SHALL round-trip
untouched through the dedicated tools.

#### Scenario: A rejected create cannot be re-applied through edit_file
- **WHEN** a `create_run_config` was rejected and the model tries `edit_file` or `write_file` on the file
- **THEN** the file tool refuses, naming the exclusion rule
- **Pinned by** `E2eRunConfigCrudTest` (e2e), `FileAccessExclusionsTest` (unit)

#### Scenario: Unknown fields survive a write
- **WHEN** an entry carries a field the agent does not model and the entry is updated
- **THEN** the field is still present afterwards
- **Pinned by** `RunConfigToolsTest` (unit)
