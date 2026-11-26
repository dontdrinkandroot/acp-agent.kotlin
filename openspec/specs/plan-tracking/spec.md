# plan-tracking Specification

## Purpose

Defines the execution plan the agent shows to the user: the `update_plan` tool, its entry
model, and the plan's persistence and replay.

## Requirements

### Requirement: update_plan replaces the whole plan
Every `update_plan` call SHALL carry the complete list of plan entries and SHALL replace the
previous plan. The agent SHALL store the entries on the session and forward them to the client
as a plan update.

#### Scenario: A call replaces the plan
- **WHEN** the model sends a plan with fewer entries than before
- **THEN** the stored plan and the client's plan show exactly the new entries
- **Pinned by** `UpdatePlanToolTest` (unit), `E2eWireConformanceTest` (e2e)

#### Scenario: The client receives a plan update
- **WHEN** `update_plan` succeeds
- **THEN** the client receives one `plan` update carrying the entries
- **Pinned by** `E2eWireConformanceTest` (e2e), `UpdatePlanToolTest` (unit)

### Requirement: Plan entries are validated strictly
Each entry SHALL carry non-empty content, a priority (`high`, `medium` or `low`) and a status
(`pending`, `in_progress` or `completed`). Entries that do not decode, or that have blank
content, SHALL fail the tool call with an error result naming the accepted shape.

#### Scenario: A valid plan is accepted
- **WHEN** all entries carry content, priority and status
- **THEN** the call succeeds and the plan is applied
- **Pinned by** `UpdatePlanToolTest` (unit)

#### Scenario: A blank content is refused
- **WHEN** an entry's content is blank
- **THEN** the call fails and the plan is unchanged
- **Pinned by** `UpdatePlanToolTest` (unit)

#### Scenario: An unknown status is refused
- **WHEN** an entry carries a status outside the three allowed values
- **THEN** the call fails with the shape message
- **Pinned by** `UpdatePlanToolTest` (unit)

#### Scenario: Entries are typed, not passed through as raw strings
- **WHEN** the plan is delivered to the client
- **THEN** it is encoded as the protocol's typed plan entries
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: update_plan is available in every mode and needs no permission
The tool SHALL be offered in plan, build and bash mode, SHALL be classified as a thinking
tool and SHALL never ask for permission.

#### Scenario: Plan mode can plan
- **WHEN** the session runs in plan mode
- **THEN** `update_plan` is among the offered tools and executes without a permission prompt
- **Pinned by** `ToolRegistryTest` (unit), `E2eModeRestrictionTest` (e2e)

### Requirement: The plan is persisted and replayed
The plan SHALL be part of the session record and SHALL be replayed on `session/load`.

#### Scenario: Plan survives a restart
- **WHEN** a session with a plan is loaded after an agent restart
- **THEN** the replayed conversation ends with the stored plan
- **Pinned by** `E2eSessionPersistenceTest` (e2e), `SessionStateRepairTest` (unit)

#### Scenario: An empty plan replays nothing
- **WHEN** a record holds no entries
- **THEN** no plan update is replayed
- **Pinned by** `ReplayHistoryTest` (unit)
