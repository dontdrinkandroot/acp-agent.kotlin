# provider-routing Specification

## Purpose

Defines the automatic provider routing preference the agent attaches to model requests: a
throughput sort with a median completion-price cap, and how it degrades when the provider feed
is unavailable.

## Requirements

### Requirement: Automatic routing sorts by throughput and caps at the median price
By default every model request SHALL carry provider preferences that sort providers by
throughput and cap the accepted completion price at the median completion price of the
selected model's endpoints, expressed in US dollars per million tokens.

#### Scenario: The auto policy reaches the wire
- **WHEN** a model request is sent with auto routing enabled and the feed available
- **THEN** the request carries `provider.sort = throughput` and a `max_price.completion` equal to the median price
- **Pinned by** `E2eProviderRoutingTest` (e2e), `ProviderRoutingTest` (unit)

#### Scenario: An even number of endpoints averages the middle two
- **WHEN** the model lists an even number of priced endpoints
- **THEN** the cap is the average of the two middle prices
- **Pinned by** `ProviderRoutingTest` (unit)

#### Scenario: Unpriceable endpoints do not distort the cap
- **WHEN** some endpoints carry an unparseable completion price
- **THEN** they are excluded from the median and the request still carries a cap
- **Pinned by** `ProviderRoutingTest` (unit), `E2eProviderRoutingTest` (e2e)

### Requirement: Routing fails open
A failed or empty provider feed, or a feed with no parseable price at all, SHALL omit the
provider field rather than failing the turn. A zero cap SHALL never be sent, because it would
exclude every provider.

#### Scenario: A failed feed sends no preferences
- **WHEN** the endpoints request fails
- **THEN** the model request carries no provider field and the turn proceeds
- **Pinned by** `ProviderRoutingTest` (unit), `E2eProviderRoutingTest` (e2e)

#### Scenario: No priceable endpoint sends no preferences
- **WHEN** every endpoint's price is unparseable
- **THEN** the request carries no provider field
- **Pinned by** `ProviderRoutingTest` (unit)

### Requirement: Disabling auto routing removes the preference
With automatic routing switched off, the agent SHALL send no provider preference of its own
and SHALL NOT fetch the provider feed for that purpose. A manual provider pick SHALL still
win.

#### Scenario: The toggle silences the policy
- **WHEN** auto routing is switched off in the configuration
- **THEN** the request carries no sort and no price cap
- **Pinned by** `E2eProviderRoutingTest` (e2e), `ProviderRoutingTest` (unit)

#### Scenario: A manual pick beats the auto policy
- **WHEN** auto routing is enabled and the client selected a provider
- **THEN** the request carries that provider's order and no automatic sort or cap
- **Pinned by** `E2eProviderOptionTest` (e2e), `PromptRunnerTest` (unit)
