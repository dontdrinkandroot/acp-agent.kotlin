# model-configuration Specification

## Purpose

Defines the per-session model, reasoning-effort and provider selections a client can make and
that the agent reports back, including their defaults, validation, reset rules and
persistence.

## Requirements

### Requirement: Session setup loads the model feed
`session/new`, `session/load` and `session/resume` SHALL load the model catalogue from the
configured endpoint, restricted to models that support tool calling and text output and
sorted by id. A failed load SHALL fail the session request, and the session's resources
SHALL be closed again.

#### Scenario: Only tool-capable text models are offered
- **WHEN** the feed lists a model without tool support or without text output
- **THEN** the model is absent from the session's model list
- **Pinned by** `E2eWireConformanceTest` (e2e), `SessionConfigOptionsTest` (unit)

#### Scenario: A failed feed fails the session request
- **WHEN** the model feed request fails during `session/new`
- **THEN** the request fails and no session is left open
- **Pinned by** `AgentSessionModelOptionsTest` (unit)

### Requirement: The model selection is per session and persisted
A session SHALL start on the configured default model and SHALL allow the client to switch
it. The selection SHALL apply to every subsequent model request of that session and SHALL be
persisted with the session record so it survives a restore.

#### Scenario: The configured default applies
- **WHEN** a session is created without a model choice
- **THEN** the session's model equals `openRouter.defaultModel`
- **Pinned by** `AgentSessionModelOptionsTest` (unit), `E2eWireConformanceTest` (e2e)

#### Scenario: A switch reaches the wire
- **WHEN** the client switches the session model
- **THEN** the next model request names the new model
- **Pinned by** `E2eWireConformanceTest` (e2e), `AgentSessionModelOptionsTest` (unit)

#### Scenario: The selection survives a restore
- **WHEN** a session with a chosen model is loaded again
- **THEN** it runs on that model
- **Pinned by** `SessionStateProviderSelectionTest` (unit), `E2eSessionPersistenceTest` (e2e)

### Requirement: The model switch resets reasoning and provider
Switching the model SHALL reset the reasoning effort and the provider selection, because
both are properties of the model.

#### Scenario: Reset on switch
- **WHEN** the client switches the model after selecting a reasoning effort and a provider
- **THEN** both selections fall back to their model defaults
- **Pinned by** `SessionConfigOptionsTest` (unit)

### Requirement: The reasoning option follows the model's capability
A reasoning option SHALL be offered when the selected model reports a reasoning block.
Its values SHALL be the model's supported efforts, or the gateway levels from `max` down to
`minimal` when the model reports none. Models without a mandatory reasoning block SHALL also
offer `none`; models with a mandatory block SHALL NOT.

#### Scenario: Supported efforts are offered
- **WHEN** the model reports a set of supported efforts
- **THEN** exactly those are selectable
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: No efforts reported means the gateway levels
- **WHEN** the model reports an empty effort list
- **THEN** `max`, `xhigh`, `high`, `medium`, `low` and `minimal` are selectable
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: Mandatory reasoning cannot be turned off
- **WHEN** the model marks reasoning as mandatory
- **THEN** `none` is not offered
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: No reasoning block hides the option
- **WHEN** the model reports no reasoning capability
- **THEN** the session offers no reasoning option
- **Pinned by** `SessionConfigOptionsTest` (unit)

### Requirement: The effective reasoning effort is applied or omitted
The selected effort, or the model's default effort when nothing was selected, SHALL be sent
as the request's reasoning effort. When reasoning is off or unsupported, the request SHALL
carry no reasoning field.

#### Scenario: The effort reaches the request
- **WHEN** the client selects a reasoning effort
- **THEN** the next model request carries that effort
- **Pinned by** `E2eWireConformanceTest` (e2e), `LlmResponseGoldensTest` (unit)

#### Scenario: Reasoning off omits the field
- **WHEN** the reasoning selection is `none`
- **THEN** the request carries no reasoning field
- **Pinned by** `LlmResponseGoldensTest` (unit), `PromptRunnerTest` (unit)

#### Scenario: The model default applies without a selection
- **WHEN** nothing was selected and the model reports a default effort
- **THEN** the request carries the model's default effort
- **Pinned by** `SessionConfigOptionsTest` (unit)

### Requirement: The provider option offers auto and the model's providers
When the model's provider feed is available, a provider option SHALL offer `auto` plus one
entry per provider of that model, valued by the provider tag and named by the provider's
display name. Blank tags and a provider tagged `auto` SHALL be skipped. `auto` SHALL always be
selectable and SHALL mean OpenRouter's own routing.

#### Scenario: Auto plus providers
- **WHEN** the feed lists providers for the model
- **THEN** the option offers `auto` first and then each provider
- **Pinned by** `SessionConfigOptionsTest` (unit), `E2eProviderOptionTest` (e2e)

#### Scenario: Unusable tags are skipped
- **WHEN** a provider has a blank tag or the tag `auto`
- **THEN** it is not offered as a select value
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: No feed hides the option
- **WHEN** the provider feed is unavailable
- **THEN** the session offers no provider option
- **Pinned by** `E2eProviderOptionTest` (e2e), `SessionConfigOptionsTest` (unit)

### Requirement: A manual provider pick restricts routing to that provider
A selected provider SHALL be sent as the request's provider order and SHALL replace the
automatic routing preferences. A slug the router does not know is harmless. `auto` SHALL NOT
send an order.

#### Scenario: A pick sends the order
- **WHEN** the client selects a provider slug
- **THEN** the request carries `provider.order` with that slug and no automatic sort/cap
- **Pinned by** `E2eProviderOptionTest` (e2e), `PromptRunnerTest` (unit)

#### Scenario: Auto sends no order
- **WHEN** the selection is `auto`
- **THEN** the request carries no order
- **Pinned by** `PromptRunnerTest` (unit), `E2eProviderOptionTest` (e2e)

### Requirement: The manual pick outlives an unavailable provider feed
A persisted manual pick SHALL keep applying to requests while the feed is unavailable, even
though the option is hidden. When the feed is available again and no longer lists the stored
slug, the selection SHALL reset to `auto`.

#### Scenario: The pick survives an outage
- **WHEN** the provider feed fails after a provider was selected
- **THEN** requests still carry that provider and the option is hidden
- **Pinned by** `SessionConfigOptionsTest` (unit), `E2eProviderOptionTest` (e2e)

#### Scenario: A stale slug heals
- **WHEN** the feed lists the model's providers and the stored slug is not among them
- **THEN** the selection resets to `auto`
- **Pinned by** `SessionConfigOptionsTest` (unit), `SessionStateProviderSelectionTest` (unit)

### Requirement: Invalid option values are refused as invalid params
An unknown option id, a value of the wrong shape, an unknown mode, an unknown reasoning value
and a provider slug not offered by the current snapshot SHALL be refused with the
invalid-params error.

#### Scenario: Unknown option id
- **WHEN** the client sets a config option the session does not offer
- **THEN** the request fails with `-32602`
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: Unknown provider slug
- **WHEN** the client selects a provider that the current snapshot does not list
- **THEN** the request fails with `-32602`
- **Pinned by** `SessionConfigOptionsTest` (unit)

#### Scenario: Wrong value shape
- **WHEN** the client sends a non-string value for a select option
- **THEN** the request fails with `-32602`
- **Pinned by** `SessionConfigOptionsTest` (unit)
