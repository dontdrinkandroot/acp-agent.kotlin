# system-prompt Specification

## Purpose

Defines what the agent tells the model before it works: the assembled system prompt, the
mode status messages in the conversation, injected project instructions and the run
configurations it may execute.

## Requirements

### Requirement: The system prompt is mode-invariant
The system prompt SHALL NOT state the current mode. It SHALL carry a static table of what
the modes mean and SHALL state that the current mode and its tools are given in a status
message in the conversation.

#### Scenario: No current mode in the prompt
- **WHEN** the same session runs a plan-mode turn and a build-mode turn
- **THEN** the system prompt text is the same in both
- **Pinned by** `SystemPromptTest` (unit)

#### Scenario: The modes table explains the modes
- **WHEN** the prompt is assembled
- **THEN** it describes plan as read-only, build as read-write and bash as build plus a permission-gated shell
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: The system prompt carries the session facts
The system prompt SHALL name the agent, the agent build (`<sha>` or `<sha>-dirty`), the
session working directory and today's date.

#### Scenario: Build and cwd are stated
- **WHEN** the prompt is assembled for a session
- **THEN** it carries the agent build identifier and the session cwd
- **Pinned by** `SystemPromptTest` (unit), `E2eWireConformanceTest` (e2e)

### Requirement: The system prompt is rebuilt for every iteration
The system prompt SHALL be assembled per model iteration, re-reading the project
instructions and the run configurations from local disk each time, so mid-session edits to
either apply.

#### Scenario: A mid-session AGENTS.md edit applies
- **WHEN** the project instructions file changes between two turns
- **THEN** the next request carries the new text
- **Pinned by** `SystemPromptTest` (unit), `E2eWireConformanceTest` (e2e)

#### Scenario: A mid-session run-configuration edit applies
- **WHEN** a run configuration is created or changed between two turns
- **THEN** the next request lists the new command
- **Pinned by** `E2eRunConfigCrudTest` (e2e)

### Requirement: Project instructions are injected as a dedicated section
`<cwd>/AGENTS.md` SHALL be injected under the heading `## Project Instructions (from
AGENTS.md)`. A missing file SHALL be ignored silently and a read error SHALL be logged
without failing the turn.

#### Scenario: Instructions are injected
- **WHEN** the session cwd holds an AGENTS.md
- **THEN** its content appears in the request under that heading
- **Pinned by** `E2eWireConformanceTest` (e2e), `SystemPromptTest` (unit)

#### Scenario: A missing file is ignored
- **WHEN** the session cwd holds no AGENTS.md
- **THEN** the request carries no project-instructions section and the turn runs normally
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: Run configurations are listed with name, command and description
The system prompt SHALL list every run configuration of `<cwd>/.ai/run.json` as name,
command and optional description, plus how to execute one (config name, optional argument
array). An absent or unreadable file SHALL leave the section out.

#### Scenario: Configurations are listed
- **WHEN** the project defines run configurations
- **THEN** each appears with its command, and configs with a description show it
- **Pinned by** `SystemPromptTest` (unit), `E2eRunToolTest` (e2e)

#### Scenario: No configurations, no section
- **WHEN** the project has no run configuration file
- **THEN** the prompt carries no run-configuration section
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: Trusted paths are rendered from the access tiers
When read or write tiers are configured, the prompt SHALL list the trusted paths: the
working directory as implicitly readable and writable, the read-only tier as available in
every mode, and the write tier as available in build and bash mode. When unlisted paths are
denied, it SHALL state that paths outside every tier are refused without a prompt.

#### Scenario: Tiers are listed
- **WHEN** readable and writable directories are configured
- **THEN** the prompt lists each tier under its mode availability
- **Pinned by** `SystemPromptTest` (unit), `E2eDirectoryTiersTest` (e2e)

#### Scenario: The deny policy is stated
- **WHEN** `permissions.unlistedPaths` is `deny`
- **THEN** the prompt states that paths outside the tiers are refused and that the user must extend the configuration
- **Pinned by** `SystemPromptTest` (unit)

#### Scenario: No tiers, no section
- **WHEN** neither tier is configured
- **THEN** the prompt carries no trusted-paths section
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: Excluded files are announced with their escape hatch
The prompt SHALL list the file-access exclusion rules and state that the file tools refuse or
hide matching files, pointing at the dedicated run-configuration tools where one exists and
otherwise at asking the user.

#### Scenario: Exclusions are announced
- **WHEN** the prompt is assembled
- **THEN** it names the exclusion rules and the run-configuration tools as the way to change that file
- **Pinned by** `SystemPromptTest` (unit), `E2eExcludedFilesTest` (e2e)

### Requirement: The mode status message is written once per governing mode
A mode status message SHALL state the mode, its semantics and the tools available in it. It
SHALL be written to the conversation at prompt start, and only when the governing mode differs
from the last indicated one, so the model sees exactly one message per mode that actually
governs a turn.

#### Scenario: The first prompt states the mode
- **WHEN** a fresh session receives its first prompt
- **THEN** one mode status message precedes the user message
- **Pinned by** `E2eModeIndicationTest` (e2e)

#### Scenario: Repeated flips coalesce
- **WHEN** the mode is switched several times without a prompt in between
- **THEN** the next prompt carries one status message, for the latest mode
- **Pinned by** `E2eModeIndicationTest` (e2e)

#### Scenario: A switch back writes nothing
- **WHEN** the mode is switched to another mode and back before the next prompt
- **THEN** no new status message is written
- **Pinned by** `E2eModeIndicationTest` (e2e)

#### Scenario: The status message names the mode's tools
- **WHEN** the mode is build
- **THEN** the message lists the tools available in build mode
- **Pinned by** `E2eModeIndicationTest` (e2e), `SystemPromptTest` (unit)

### Requirement: The indicated mode is restored from the trail
A restored session SHALL take the last indicated mode from its conversation's mode status
messages, so a mode that governed a turn is not re-announced. A record without any such
message SHALL get one at its next prompt.

#### Scenario: A restored trail is not doubled
- **WHEN** a session whose trail ends with its current mode's status message is loaded and prompted
- **THEN** no additional status message is written
- **Pinned by** `SessionStateDeferredModeTest` (unit), `E2eSessionPersistenceTest` (e2e)

#### Scenario: A legacy record self-heals
- **WHEN** a record carries no mode status message
- **THEN** the next prompt writes one for the governing mode
- **Pinned by** `SessionStateDeferredModeTest` (unit)

### Requirement: The prompt carries plan guidance in every mode
The system prompt SHALL state that `update_plan` is available in every mode and that a plan
outlives mode switches and turns, and SHALL instruct the model when to use it: for three or
more distinct steps, many tool calls or an explicit request, never for single-step work or as
padding.

#### Scenario: Plan usage is guided
- **WHEN** the prompt is assembled
- **THEN** it states the plan's availability in every mode and the trigger for using it
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: The prompt guides the plan's lifecycle
The system prompt SHALL instruct the model to keep the plan live - exactly one entry
`in_progress`, entries marked `completed` as soon as they are done - and to revise it when the
work changes: add discovered steps, drop unnecessary ones, split a coarse one, reorder. It
SHALL state that a plan no longer matching the work is a defect, that finished entries are
consolidated into one completed entry per theme, and that closing the turn requires nothing
left `in_progress`.

#### Scenario: The lifecycle rules are stated
- **WHEN** the prompt is assembled
- **THEN** it requires one `in_progress` entry, immediate completion, revision on change, consolidation and an empty `in_progress` list at turn end
- **Pinned by** `SystemPromptTest` (unit)

#### Scenario: Plan mode hands back the settled plan
- **WHEN** the session runs in plan mode
- **THEN** the prompt states that the settled plan is what the turn hands back
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: The prompt states that the plan has no blocked status
The system prompt SHALL state that the plan has no `blocked` status, so a blocker is reported
in the reply instead of being encoded as a status.

#### Scenario: The missing status is stated
- **WHEN** the prompt is assembled
- **THEN** it states that no `blocked` status exists and that blockers go into the reply
- **Pinned by** `SystemPromptTest` (unit)

### Requirement: The prompt forbids restating a plan it already sent
The system prompt SHALL forbid repeating a plan that `update_plan` already delivered, because
the client renders it; the reply summarizes what changed and what comes next instead.

#### Scenario: Restatement is forbidden
- **WHEN** the prompt is assembled
- **THEN** its communication rules forbid repeating a just-sent plan
- **Pinned by** `SystemPromptTest` (unit)
