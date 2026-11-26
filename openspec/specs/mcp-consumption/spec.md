# mcp-consumption Specification

## Purpose

Defines how the agent consumes MCP servers the client hands it at session creation: the
supported transports, the connection lifecycle, how server tools become agent tools, and how a
stdio server is launched.

## Requirements

### Requirement: Servers come only from the client's session request
The agent SHALL connect only to the servers listed in `session/new`'s `mcpServers`; it SHALL
never discover or configure servers on its own.

#### Scenario: The declared servers are connected
- **WHEN** the client declares servers for a session
- **THEN** each is connected and its tools are offered in that session
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `E2eMcpStdioTransportTest` (e2e)

### Requirement: All three transports are supported
The agent SHALL support the stdio, streamable HTTP and classic SSE transport variants and SHALL
advertise HTTP and SSE MCP capabilities.

#### Scenario: stdio server
- **WHEN** the client declares a stdio server
- **THEN** the server process is started and its tools work
- **Pinned by** `E2eMcpStdioTransportTest` (e2e), `McpConnectorTest` (unit)

#### Scenario: streamable HTTP server
- **WHEN** the client declares an HTTP server
- **THEN** its tools work over that transport
- **Pinned by** `E2eMcpToolPermissionTest` (e2e)

#### Scenario: plain SSE server
- **WHEN** the client declares a classic SSE server
- **THEN** its tools work over that transport
- **Pinned by** `E2eMcpSseTransportTest` (e2e)

#### Scenario: An HTTP server without a GET stream still works
- **WHEN** the server answers 405 to the optional GET stream probe
- **THEN** the connection works JSON-only
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `McpConnectorTest` (unit)

### Requirement: Server-declared headers are forwarded
HTTP headers declared with an MCP server SHALL be sent on every request of that connection.

#### Scenario: Headers reach the server
- **WHEN** the client declares headers for an SSE server
- **THEN** the server receives them
- **Pinned by** `E2eMcpSseTransportTest` (e2e)

### Requirement: A stdio server starts in the session cwd with a built environment
A stdio server process SHALL start in the session's working directory - never the directory the
agent was launched in - and SHALL receive an allowlisted environment of process basics
(`PATH`, `HOME`, temporary-directory and locale variables, `TERM`, `TZ`, `USER`, `LOGNAME`)
plus the server-declared entries, which win. Other agent environment variables SHALL NOT be
inherited.

#### Scenario: The child sees the session directory
- **WHEN** the agent is launched from a different directory than the session cwd
- **THEN** the stdio server's working directory is the session cwd
- **Pinned by** `E2eMcpStdioTransportTest` (e2e)

#### Scenario: Secrets are not inherited
- **WHEN** the agent environment carries a variable outside the allowlist
- **THEN** the child does not see it
- **Pinned by** `McpConnectorTest` (unit), `E2eMcpStdioTransportTest` (e2e)

#### Scenario: A declared variable can set anything
- **WHEN** the server declaration carries an environment entry
- **THEN** the child sees exactly that value, even overriding an allowlisted one
- **Pinned by** `McpConnectorTest` (unit), `E2eMcpStdioTransportTest` (e2e)

### Requirement: Server tools become agent tools with the server's schema
Each tool a server offers SHALL be bridged into the session as a tool of the same name, with the
server's description and its input schema forwarded verbatim, so required arguments and schema
definitions reach the model unchanged.

#### Scenario: The schema round-trips
- **WHEN** a server's tool schema declares required properties
- **THEN** the model request carries the schema unchanged
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `McpBridgeTest` (unit)

#### Scenario: A tool result is returned as text
- **WHEN** an MCP tool executes
- **THEN** its text content becomes the tool result and its error flag is honoured
- **Pinned by** `E2eMcpToolPermissionTest` (e2e), `McpBridgeTest` (unit)

### Requirement: A name collision never shadows a local tool
A server tool whose name matches a local tool SHALL be ignored with a warning; the local tool
SHALL stay callable.

#### Scenario: A read_file-named server tool is ignored
- **WHEN** the server offers a tool named `read_file`
- **THEN** the local `read_file` runs and the server tool is not offered
- **Pinned by** `E2eMcpStdioTransportTest` (e2e), `McpBridgeTest` (unit)

### Requirement: A failing server fails open
A server that cannot be started or initialized SHALL be skipped with a logged warning; the
session SHALL still be created and the other servers SHALL work.

#### Scenario: An unstartable server
- **WHEN** a declared stdio server's command cannot be executed
- **THEN** the session is created and a warning is logged
- **Pinned by** `E2eMcpStdioTransportTest` (e2e)

### Requirement: A session's server connections end with it
Closing or deleting a session SHALL close its MCP connections and terminate its stdio child
processes.

#### Scenario: Close tears the servers down
- **WHEN** the client closes a session with a stdio server
- **THEN** the child process is terminated
- **Pinned by** `E2eSessionPersistenceTest` (e2e), `SessionLifecycleTest` (unit)
