# utility-tools Specification

## Purpose

Defines the two small utility tools: `calc` for safe arithmetic and `get_current_mode` for
asking which mode governs the session.

## Requirements

### Requirement: calc evaluates arithmetic with standard precedence
`calc` SHALL evaluate an arithmetic expression and return its numeric result, accepting
decimal and scientific numbers, the operators `+`, `-`, `*`, `/`, `%` and `^` with standard
precedence (`^` right-associative), unary sign and parentheses.

#### Scenario: Precedence and associativity
- **WHEN** the expression is `2 + 3 * 4` or `2 ^ 3 ^ 2`
- **THEN** the result follows standard precedence and right-associative exponentiation
- **Pinned by** `CalcExpressionTest` (unit)

#### Scenario: Unary sign and parentheses
- **WHEN** the expression is `-(2 + 3)`
- **THEN** the result is `-5`
- **Pinned by** `CalcExpressionTest` (unit)

### Requirement: calc offers the documented functions and constants
`calc` SHALL support the functions `sqrt`, `abs`, `min`, `max`, `floor`, `ceil`, `round`,
`sin`, `cos`, `tan`, `log` (base 10), `ln` and `exp`, and the constants `pi` and `e`.
`min` and `max` SHALL accept one or more arguments; every other function SHALL accept exactly
one.

#### Scenario: Functions and constants
- **WHEN** the expression uses `sqrt(min(9, 16))` or `pi`
- **THEN** the result is the numeric value
- **Pinned by** `CalcExpressionTest` (unit)

#### Scenario: Wrong arity is refused
- **WHEN** a function is called with the wrong number of arguments
- **THEN** the call fails naming the arity
- **Pinned by** `CalcExpressionTest` (unit)

### Requirement: calc is pure math with an advertised language
The expression language SHALL have no variables, no assignment and no I/O, and the tool
description SHALL advertise exactly the supported language so the model knows what it may
write.

#### Scenario: The language is fully described
- **WHEN** the model reads the tool description
- **THEN** it lists every operator, function and constant with its arity
- **Pinned by** `CalcToolTest` (unit)

#### Scenario: A variable is refused
- **WHEN** the expression names a variable or assigns a value
- **THEN** the call fails with a positional error
- **Pinned by** `CalcExpressionTest` (unit)

### Requirement: calc errors name the position
A malformed expression SHALL fail with a message naming the unexpected token and its position,
not with a stack trace or a generic error.

#### Scenario: A stray parenthesis
- **WHEN** the expression is `(1))`
- **THEN** the error names `)` and its position
- **Pinned by** `CalcExpressionTest` (unit), `CalcToolTest` (unit)

### Requirement: calc refuses unsafe or meaningless evaluation
`calc` SHALL refuse an expression longer than 1000 characters, nesting deeper than 64 levels,
division or modulo by zero, and any result that is not finite.

#### Scenario: Length cap
- **WHEN** the expression exceeds 1000 characters
- **THEN** the call fails naming the cap
- **Pinned by** `CalcExpressionTest` (unit)

#### Scenario: Nesting cap
- **WHEN** the parentheses nest deeper than 64 levels
- **THEN** the call fails naming the cap
- **Pinned by** `CalcExpressionTest` (unit)

#### Scenario: Division by zero
- **WHEN** the expression divides by zero
- **THEN** the call fails instead of returning infinity or NaN
- **Pinned by** `CalcExpressionTest` (unit)

#### Scenario: Non-finite result
- **WHEN** the computed value overflows or is otherwise not finite
- **THEN** the call fails
- **Pinned by** `CalcExpressionTest` (unit)

### Requirement: calc renders integral results without a decimal point
A whole-number result SHALL be rendered without a fractional part, and other results with
their decimal value.

#### Scenario: No trailing .0
- **WHEN** the expression is `4 / 2`
- **THEN** the result reads `2`
- **Pinned by** `CalcToolTest` (unit)

#### Scenario: A fractional result keeps its decimals
- **WHEN** the expression is `1 / 4`
- **THEN** the result reads `0.25`
- **Pinned by** `CalcToolTest` (unit)

### Requirement: Utility tools are available everywhere and never ask
`calc` and `get_current_mode` SHALL be offered in plan, build and bash mode, SHALL be
non-mutating and SHALL never ask for permission. `get_current_mode` SHALL take no parameters.

#### Scenario: Prompt-free in plan mode
- **WHEN** either tool is called in plan mode
- **THEN** it executes without a permission request
- **Pinned by** `E2eModeRestrictionTest` (e2e), `ToolRegistryTest` (unit)

### Requirement: get_current_mode answers with the governing mode's status text
`get_current_mode` SHALL return the same text the conversation's mode status messages carry:
the mode, its semantics and the tools available in it, reflecting the mode captured when the
turn started.

#### Scenario: The answer states the mode and its tools
- **WHEN** the tool is called in build mode
- **THEN** the result names build mode, its semantics and the tools available in it
- **Pinned by** `GetCurrentModeToolTest` (unit), `E2eModeRestrictionTest` (e2e)

#### Scenario: A mid-turn mode request is not reflected
- **WHEN** the client requested another mode while the turn runs
- **THEN** the answer still states the mode that governs the turn
- **Pinned by** `GetCurrentModeToolTest` (unit)
