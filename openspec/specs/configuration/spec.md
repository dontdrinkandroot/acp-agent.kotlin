# configuration Specification

## Purpose

Defines how the agent is configured: the configuration file and profiles, what a project may
add on top, the API key rule, the permission tiers as configuration, and where session state
lives.

## Requirements

### Requirement: Configuration comes from files only
The effective configuration SHALL be built from built-in defaults, overridden by the selected
`config.json` and then by the project overlay. No environment variable SHALL carry an agent
setting; a `DDR_ACP_AGENT_*` variable SHALL be ignored silently.

#### Scenario: An environment variable changes nothing
- **WHEN** a `DDR_ACP_AGENT_*` variable is set
- **THEN** the effective configuration is unchanged and no warning is printed
- **Pinned by** `ConfigTest` (unit)

#### Scenario: The file wins over the default
- **WHEN** the configuration file sets a value
- **THEN** that value applies instead of the built-in default
- **Pinned by** `ConfigFileTest` (unit), `ConfigTest` (unit)

### Requirement: The configuration file lives in the XDG config home
The agent SHALL read `$XDG_CONFIG_HOME/ddr-acp-agent/config.json`, falling back to
`~/.config/ddr-acp-agent/config.json`, once at startup.

#### Scenario: The file is read at startup
- **WHEN** the configuration changes after startup
- **THEN** the running agent keeps the values it started with until restarted
- **Pinned by** `ConfigTest` (unit)

### Requirement: A profile selects another configuration file
`--profile <name>` SHALL select `<name>.json` in the configuration directory. The value
SHALL be a bare name (a `.json` suffix is rejected), a selected profile SHALL exist, and a
missing default file SHALL mean "all defaults".

#### Scenario: A profile file is selected
- **WHEN** the agent starts with `--profile work`
- **THEN** `work.json` is read
- **Pinned by** `ConfigTest` (unit), `E2eStartupArgumentsTest` (e2e)

#### Scenario: A suffixed profile is rejected
- **WHEN** the value ends in `.json`
- **THEN** startup fails with a message naming the rule
- **Pinned by** `AgentArgumentsTest` (unit)

#### Scenario: A missing profile fails
- **WHEN** the selected profile file does not exist
- **THEN** startup fails instead of silently using defaults
- **Pinned by** `ConfigTest` (unit)

#### Scenario: No default file means defaults
- **WHEN** no configuration file exists and no profile was selected
- **THEN** the agent starts on the built-in defaults
- **Pinned by** `ConfigTest` (unit)

### Requirement: The parser is strict about values and lenient about unknown keys
A wrongly typed or out-of-range value SHALL fail startup with a message naming the file and the
JSON path. An unknown key in `config.json` SHALL be ignored, so a newer file still works with
an older agent.

#### Scenario: An out-of-range value
- **WHEN** `tools.bash.timeoutSeconds` is `-5`
- **THEN** startup fails naming that path and the accepted range
- **Pinned by** `ConfigFileTest` (unit)

#### Scenario: An unknown key is ignored
- **WHEN** the file carries a key the agent does not know
- **THEN** startup succeeds
- **Pinned by** `ConfigFileTest` (unit), `ConfigTest` (unit)

### Requirement: The API key is read from a file only
The key SHALL be resolved from `openRouter.apiKeyFile` first and then from
`<config dir>/openrouter.key`. If neither resolves, startup SHALL fail listing the sources
tried. A key file readable by group or others SHALL be refused, naming its path.

#### Scenario: The configured path wins
- **WHEN** `openRouter.apiKeyFile` names an existing owner-only file
- **THEN** that key is used
- **Pinned by** `ConfigTest` (unit)

#### Scenario: The default location is used
- **WHEN** no key path is configured and the default file exists
- **THEN** that key is used
- **Pinned by** `ConfigTest` (unit), `E2eInfoCommandTest` (e2e)

#### Scenario: No key fails startup
- **WHEN** no key file resolves
- **THEN** startup fails listing the paths tried
- **Pinned by** `ConfigTest` (unit)

#### Scenario: A too-open key file is refused
- **WHEN** the key file is readable by others
- **THEN** startup fails naming the file
- **Pinned by** `ConfigTest` (unit)

### Requirement: The behaviour switches have validated defaults
The agent SHALL apply these defaults and reject invalid values: `openRouter.defaultModel`
(`openrouter/auto`), `openRouter.baseUrl`, `openRouter.autoThroughputSorting` (enabled),
`tools.maxTurnRequests` (100, at least 1), `tools.fsProxy` (enabled),
`tools.bash.timeoutSeconds` (600, 1 to 3600), `tools.webFetch.allowPrivate` (disabled) and
`mcp.trustAnnotations` (enabled).

#### Scenario: The defaults apply
- **WHEN** no configuration file exists
- **THEN** the agent runs with the documented defaults
- **Pinned by** `ConfigTest` (unit)

#### Scenario: A zero iteration budget is refused
- **WHEN** `tools.maxTurnRequests` is 0
- **THEN** startup fails naming the minimum
- **Pinned by** `ConfigFileTest` (unit)

### Requirement: The permission tiers are configuration
`permissions.readableDirectories` and `permissions.writableDirectories` SHALL hold absolute
paths, never `/`, with a trailing slash normalized away. A missing directory SHALL be reported
as a warning in a direct run. `permissions.unlistedPaths` SHALL accept `prompt` (default) and
`deny`.

#### Scenario: Invalid entries are refused
- **WHEN** a tier entry is relative or `/`
- **THEN** startup fails naming the entry
- **Pinned by** `ConfigTest` (unit)

#### Scenario: A trailing slash is normalized
- **WHEN** an entry ends with `/`
- **THEN** it names the same directory as without it
- **Pinned by** `ConfigTest` (unit)

#### Scenario: A missing directory only warns
- **WHEN** a configured directory does not exist in a direct run
- **THEN** a warning is logged and the agent starts
- **Pinned by** `ConfigTest` (unit)

### Requirement: The project overlay may only extend permissions
`<launch cwd>/.ai/ddr-acp-agent.json` SHALL be read at startup and MAY contain only
`permissions.*`. Any other key SHALL fail startup with a message saying where it belongs, and an
unknown key SHALL fail as well. The directory lists SHALL be unioned with the global lists;
`unlistedPaths` SHALL replace the global value. Every named directory SHALL be absolute, shall
not be `/`, and SHALL exist, or startup fails.

#### Scenario: The overlay adds a directory
- **WHEN** the project file lists an extra readable directory
- **THEN** that directory is trusted for the session without changing the global file
- **Pinned by** `ProjectConfigTest` (unit), `E2eProjectConfigTest` (e2e)

#### Scenario: A non-permission key is refused
- **WHEN** the project file sets a tool or model setting
- **THEN** startup fails naming the key and its home
- **Pinned by** `ProjectConfigTest` (unit)

#### Scenario: An unknown key is refused
- **WHEN** the project file carries a key the overlay does not define
- **THEN** startup fails
- **Pinned by** `ProjectConfigTest` (unit)

#### Scenario: A missing directory fails
- **WHEN** the project file names a directory that does not exist
- **THEN** startup fails naming the path, because the file belongs to this checkout
- **Pinned by** `ProjectConfigTest` (unit), `E2eProjectConfigTest` (e2e)

#### Scenario: The overlay is read from the launch directory only
- **WHEN** a session is opened on a different directory than the agent was launched in
- **THEN** the launch directory's overlay applies and the session directory's is not read
- **Pinned by** `ProjectConfigTest` (unit)

#### Scenario: The overlay is a file-tool exclusion
- **WHEN** the model tries to read or write the overlay file
- **THEN** the file tools refuse it
- **Pinned by** `FileAccessExclusionsTest` (unit), `E2eExcludedFilesTest` (e2e)

### Requirement: The sandbox group is not agent configuration
The `sandbox` group of `config.json` SHALL be read only by the sandbox supervisor; the agent
SHALL ignore it and the project overlay SHALL NOT set it.

#### Scenario: Sandbox keys do not affect the agent
- **WHEN** the configuration carries a `sandbox` group
- **THEN** the agent starts normally
- **Pinned by** `ConfigTest` (unit), `ProjectConfigTest` (unit)

### Requirement: Session state lives in the XDG state home
Session records SHALL live under `$XDG_STATE_HOME/ddr-acp-agent/sessions/`, falling back to
`~/.local/state/ddr-acp-agent/sessions/`, one file per session.

#### Scenario: The state home is honoured
- **WHEN** `XDG_STATE_HOME` points at a directory
- **THEN** records are written below it
