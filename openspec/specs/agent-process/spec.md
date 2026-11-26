# agent-process Specification

## Purpose

Defines the agent as a process: how it starts, which protocol capabilities and versions it
answers with, what it writes where, how it identifies its build, what `/info` reports, and how
it shuts down.

## Requirements

### Requirement: The process speaks ACP on stdout and logs on stderr
Every byte the agent writes to stdout SHALL be a protocol message. All logging SHALL go to
stderr, and no startup banner SHALL be printed there either.

#### Scenario: stdout stays protocol-clean
- **WHEN** the agent runs a full session lifetime
- **THEN** every line the client reads on stdout parses as a JSON-RPC 2.0 message
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: Startup arguments are strict
The agent SHALL accept only `--profile <name>` as an argument. Any other argument SHALL end
startup with exit code 2 and a one-line message naming it.

#### Scenario: An unknown argument exits 2
- **WHEN** the agent is started with an argument it does not know
- **THEN** it exits with code 2 and a message naming the argument
- **Pinned by** `E2eStartupArgumentsTest` (e2e), `AgentArgumentsTest` (unit)

#### Scenario: The selected profile is the file read
- **WHEN** the agent is started with `--profile work`
- **THEN** it reads `work.json` as its configuration
- **Pinned by** `E2eStartupArgumentsTest` (e2e), `ConfigTest` (unit)

### Requirement: A broken configuration ends startup with a readable error
A configuration file that cannot be parsed or validated SHALL end startup with exit code 2 and
one `acp-agent: <message>` line naming the file and the failing JSON path, without a stack
trace.

#### Scenario: An invalid value fails startup
- **WHEN** the configuration holds an out-of-range or wrongly typed value
- **THEN** the agent exits 2 with a message naming the key
- **Pinned by** `ConfigFileTest` (unit), `ConfigTest` (unit)

### Requirement: initialize advertises the agent's capabilities
`initialize` SHALL advertise session load, list, resume, delete and close, prompt support for
images and embedded context (not audio), and HTTP and SSE MCP transports, plus the agent's
implementation name and version.

#### Scenario: The capability set
- **WHEN** the client initializes
- **THEN** the response carries exactly those capabilities
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: Version negotiation answers with the supported version
The agent SHALL echo a requested protocol version it supports and SHALL answer an unsupported
requested version with its own latest supported version on that connection.

#### Scenario: v1 is echoed
- **WHEN** the client requests protocol version 1
- **THEN** the response carries version 1
- **Pinned by** `E2eWireConformanceTest` (e2e)

#### Scenario: An unsupported version gets the latest supported
- **WHEN** the client requests a newer protocol version
- **THEN** the response carries the agent's latest supported version
- **Pinned by** `E2eWireConformanceTest` (e2e)

### Requirement: The agent reports its build
The agent SHALL report its build as the git commit hash plus a `-dirty` marker when the
checkout had uncommitted changes, or `unknown` outside a git checkout. The value SHALL appear in
the system prompt, in `/info` and in the protocol `initialize` response where the version is
carried.

#### Scenario: The build hash round-trips
- **WHEN** the agent runs from a git checkout
- **THEN** the reported build matches the checkout's commit
- **Pinned by** `BuildInfoTest` (unit), `E2eWireConformanceTest` (e2e)

### Requirement: /info is advertised and answered locally
Right after every session setup the agent SHALL advertise the `/info` slash command as the
complete command list. A `session/prompt` whose single text block is `/info` or starts with
`/info` followed by whitespace SHALL be answered with one Markdown message chunk and an
`end_turn` response, without touching session state: no history entry, no record, no model
call, no tokens.

#### Scenario: The command is advertised
- **WHEN** a session is created, loaded or resumed
- **THEN** an `available_commands_update` carrying `info` is sent
- **Pinned by** `E2eInfoCommandTest` (e2e), `InfoCommandTest` (unit)

#### Scenario: The reply costs no tokens
- **WHEN** `/info` is sent
- **THEN** one message chunk reports version, build, session facts, model, reasoning, provider, switches and trusted paths, and no model request is made
- **Pinned by** `E2eInfoCommandTest` (e2e), `AgentSessionInfoTest` (unit)

#### Scenario: The key is never printed
- **WHEN** the reply names the API key
- **THEN** it names the key file, never the key's value
- **Pinned by** `InfoCommandTest` (unit)

#### Scenario: Extra text is reported as discarded
- **WHEN** `/info` is followed by more text
- **THEN** the reply leads with a note naming the discarded text
- **Pinned by** `InfoCommandTest` (unit), `E2eInfoCommandTest` (e2e)

#### Scenario: A lookalike is an ordinary prompt
- **WHEN** the prompt is `/infofoo`, mentions `/info` in prose, or carries a second content block
- **THEN** it is sent to the model as a normal prompt
- **Pinned by** `InfoCommandTest` (unit), `E2eInfoCommandTest` (e2e)

#### Scenario: /info leaves no trace
- **WHEN** a session is used only for `/info`
- **THEN** no session record exists while the turn runs or after the reply, and the session stays untitled
- **Pinned by** `E2eInfoCommandTest` (e2e), `AgentSessionInfoTest` (unit)

### Requirement: Shutdown flushes the live sessions
When the transport closes - typically because the client closed stdin - the agent SHALL first
cancel pending incoming requests, then persist every live session, then close the protocol, so
a turn interrupted by the disconnect is not lost.

#### Scenario: A turn cut off by stdin close is persisted
- **WHEN** the client closes stdin while a turn runs
- **THEN** the session record holds the user message and a pair-complete history
- **Pinned by** `E2eShutdownTest` (e2e), `SessionLifecycleTest` (unit)

#### Scenario: Only live sessions are flushed
- **WHEN** the shutdown flush runs
- **THEN** sessions that were deleted or closed are not written again
- **Pinned by** `SessionLifecycleTest` (unit)
