# docker-sandbox Specification

## Purpose

Defines the sandbox entry point: how the agent is launched inside a Docker container with the
host project, the host's caches and exactly the configured trust paths, and how the supervisor
process relays the protocol without interpreting it.

## Requirements

### Requirement: The sandbox has its own entry point
The repository SHALL provide two launch wrappers: a direct one that execs the agent, and a
sandbox one that ensures the container image and execs the supervisor. The only argument of
either is `--profile <name>`, which both sides parse and forward.

#### Scenario: An unknown argument exits 2
- **WHEN** the sandbox wrapper is started with an argument other than `--profile`
- **THEN** it exits with code 2
- **Pinned by** `SandboxSupervisorTest` (unit), `E2eStartupArgumentsTest` (e2e)

#### Scenario: The profile reaches the agent
- **WHEN** the wrapper is started with `--profile work`
- **THEN** the containerized agent's argv carries the same flag and value
- **Pinned by** `SandboxCompositionPinTest` (unit), `SandboxSupervisorTest` (unit)

### Requirement: The wrapper never pulls the image
The sandbox wrapper SHALL inspect the image and build it locally when it is missing or when a
rebuild is requested. It SHALL never pull, so updating the upstream image stays an explicit
`docker pull`.

#### Scenario: A missing image is built
- **WHEN** the image is not present locally
- **THEN** the wrapper builds it with the local build script before running it
- **Pinned by** `SandboxSupervisorTest` (unit), `DockerfileBuildInputsTest` (unit)

#### Scenario: An existing image is used
- **WHEN** the image is present locally and no rebuild was requested
- **THEN** no build runs
- **Pinned by** `SandboxSupervisorTest` (unit)

### Requirement: The supervisor relays bytes and never parses the protocol
The supervisor SHALL copy the container's stdout to its own stdout and the container's stderr
to its own stderr without merging them, SHALL NOT interpret ACP messages, and SHALL write
nothing to stdout itself.

#### Scenario: Both streams stay separate
- **WHEN** the container writes to stdout and stderr
- **THEN** each arrives on the matching stream of the supervisor
- **Pinned by** `SandboxSupervisorTest` (unit)

#### Scenario: The relay is byte-transparent
- **WHEN** the client sends a request through the supervisor
- **THEN** the container receives the same bytes and its answer reaches the client unchanged
- **Pinned by** `SandboxSupervisorTest` (unit)

### Requirement: stdin EOF is a half-close, not a kill
When the supervisor's stdin reaches EOF it SHALL close the child's stdin and keep waiting, so
the agent can finish its shutdown flush. Signals sent to the supervisor SHALL be forwarded to
the container, and the container's exit code SHALL become the supervisor's exit code.

#### Scenario: EOF does not kill the container
- **WHEN** the client closes the supervisor's stdin
- **THEN** the container keeps running until it exits on its own and its final output still arrives
- **Pinned by** `SandboxSupervisorTest` (unit)

#### Scenario: SIGTERM is forwarded
- **WHEN** the supervisor receives SIGTERM
- **THEN** the container receives it and the supervisor waits a bounded time
- **Pinned by** `SandboxSupervisorTest` (unit)

#### Scenario: The exit code propagates
- **WHEN** the container exits with a non-zero code
- **THEN** the supervisor exits with the same code
- **Pinned by** `SandboxSupervisorTest` (unit)

#### Scenario: A missing docker is named
- **WHEN** no docker CLI is resolvable on `PATH`
- **THEN** the supervisor exits 127 with a message naming docker
- **Pinned by** `SandboxSupervisorTest` (unit)

### Requirement: The project is mounted at its identical path
The launch directory SHALL be bind-mounted read-write at the identical in-container path and
SHALL be the container's working directory, so paths in the agent's answers stay valid on the
host. The launch directory is assumed to be the session working directory.

#### Scenario: The project bind and workdir
- **WHEN** a sandbox is started from a project directory
- **THEN** the argv carries that directory as both mount and workdir
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: The container is hardened
The container SHALL run with all capabilities dropped, `no-new-privileges`, a read-only root
filesystem unless `sandbox.rwRootfs` is set, a tmpfs at `/tmp`, and the host user's uid and gid.

#### Scenario: The hardening flags
- **WHEN** the container command is composed
- **THEN** it carries the dropped capabilities, the non-privileged flag, the read-only rootfs and the host uid/gid
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: The uid matches the host user
- **WHEN** the supervisor runs as a user
- **THEN** the container runs with that user's real uid and gid
- **Pinned by** `SandboxSupervisorTest` (unit)

#### Scenario: A writable rootfs is opt-in
- **WHEN** `sandbox.rwRootfs` is true
- **THEN** the read-only flag is absent
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: Host networking is the default
The container SHALL use the host network namespace by default, so the container can reach
host-loopback services and Gradle daemons can hand over their locks. `sandbox.network` SHALL
select an explicit network instead, and the host gateway name SHALL be mapped either way.

#### Scenario: Host network by default
- **WHEN** no network is configured
- **THEN** the argv carries host networking and the loopback host mapping
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: An explicit network
- **WHEN** `sandbox.network` names a network
- **THEN** that network is used and the gateway mapping points at the host gateway
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: The container home is a private overlay
The container home SHALL be a tmpfs, or the configured named volume, with uid-mapped tmpfs
mounts for `~/.local`, `~/.local/share`, `~/.local/state` and - when a config directory is
mounted - `~/.config`, so no mount point is created root-owned inside the container.

#### Scenario: The home overlay mounts
- **WHEN** the container command is composed
- **THEN** it carries the home tmpfs and the per-directory uid mounts
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: A named volume replaces the home tmpfs
- **WHEN** `sandbox.homeVolume` is set
- **THEN** the volume is mounted as home instead of the tmpfs
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: Host tool caches are shared at their own paths
With cache sharing enabled, a cache configured by environment variable SHALL be mounted at its
identical in-container path with the variable forwarded unchanged; a set-but-unusable cache
configuration (relative, `/`, uncreatable) SHALL fail loudly. Without a configured variable, an
existing default cache directory SHALL be mounted at the tool's in-container default and the
variable SHALL be pinned to it. `sandbox.mountCaches: false` SHALL disable all cache mounts and
cache forwarding.

#### Scenario: An explicit cache is identity-mounted
- **WHEN** a cache variable such as `UV_CACHE_DIR` is set
- **THEN** the directory is mounted at the same path and the variable is forwarded verbatim
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: An invalid cache configuration fails before docker runs
- **WHEN** a cache variable points at a relative path, `/` or an uncreatable directory
- **THEN** the supervisor fails before starting the container
- **Pinned by** `SandboxFailLoudlyTest` (unit)

#### Scenario: The toggle disables sharing
- **WHEN** `sandbox.mountCaches` is false
- **THEN** no cache mount and no cache variable is composed
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: Gradle shares only its caches
The whole Gradle user home SHALL NOT be shared. Instead the container SHALL get a private,
exec-allowed Gradle home tmpfs and, on top of it, the shared content-addressed cache leaves -
downloaded modules, wrapper distributions, JDKs and the version-bound transform and generated-jar
caches - so host and container daemons never share a daemon registry, log or configuration-cache
key.

#### Scenario: Cache leaves are shared read-write
- **WHEN** a host Gradle home exists and cache sharing is enabled
- **THEN** the cache leaves are mounted at their in-container paths over a private Gradle home
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: Version directories from wrapper distributions are shared
- **WHEN** the host holds wrapper distributions for a Gradle version that has no cache directory yet
- **THEN** that version's cache directory is still shared
- **Pinned by** `SandboxCompositionPinTest` (unit)

### Requirement: The Android SDK is mounted only when it is real
An explicit `ANDROID_HOME` or `ANDROID_SDK_ROOT` SHALL win over the other, be identity-mounted
and forwarded; a set-but-unusable value SHALL fail loudly. Without a variable, an existing
default SDK directory SHALL be relocated to the container default with both variables pinned.

#### Scenario: Explicit SDK
- **WHEN** `ANDROID_HOME` is set to an existing directory
- **THEN** it is mounted at the same path and both variables carry that path
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: An unusable SDK path fails loudly
- **WHEN** the SDK variable is relative, `/`, or names a missing directory
- **THEN** the supervisor fails before starting the container
- **Pinned by** `SandboxFailLoudlyTest` (unit)

### Requirement: The permission lists are the mount list
The merged `permissions.*` lists (file plus project overlay) SHALL be mounted at their identical
paths - readable read-only, writable read-write, a path in both mounted read-write once - before
the built-in mounts, shallowest first. A configured directory that does not exist SHALL abort
the sandbox.

#### Scenario: Tiers mount with their mode
- **WHEN** both permission lists name directories
- **THEN** each is mounted with the matching mode at its host path
- **Pinned by** `SandboxCompositionPinTest` (unit), `SandboxPlanTest` (unit)

#### Scenario: A duplicate entry mounts once, writable
- **WHEN** a path is listed as both readable and writable
- **THEN** exactly one read-write mount is emitted
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: A missing directory aborts
- **WHEN** a configured directory does not exist
- **THEN** the supervisor fails before starting the container
- **Pinned by** `SandboxFailLoudlyTest` (unit), `SandboxPlanTest` (unit)

#### Scenario: Paths under a covered root are skipped with a warning
- **WHEN** a permission entry lies inside the project, the container home or a tmpfs path
- **THEN** no extra mount is emitted and a warning states the skip
- **Pinned by** `SandboxPlanTest` (unit), `SandboxCompositionPinTest` (unit)

### Requirement: Session state and configuration are shared the same way the agent reads them
The host session state directory SHALL be mounted read-write at the container's state path
(`sandbox.stateDir` overrides the host side), and an existing host config directory SHALL be
mounted read-only at the identical in-container path, because that mount is the agent's only key
source. A state or config directory inside the project directory SHALL be skipped with a typed
warning.

#### Scenario: State is shared read-write
- **WHEN** the sandbox starts
- **THEN** the host state directory is mounted at the container's state path
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: The config directory is read-only
- **WHEN** the host config directory exists
- **THEN** it is mounted read-only at the same path over the `~/.config` tmpfs
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: A config directory inside the project is skipped
- **WHEN** the config directory lies inside the project directory
- **THEN** it is not mounted and a warning explains why
- **Pinned by** `SandboxCompositionPinTest` (unit), `SandboxPlanTest` (unit)

### Requirement: Only host facts, never agent configuration, are forwarded
The supervisor SHALL forward the host time zone, the host git identity when configured, its own
`HOME`, `TERM` and XDG pins, and SHALL mask a host `.env.local` with an empty read-only file. It
SHALL NOT forward any agent configuration variable.

#### Scenario: The time zone is forwarded
- **WHEN** the host sets `TZ`
- **THEN** the container receives it
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: The git identity is forwarded
- **WHEN** the host git config defines user name and email
- **THEN** the author and committer variables are forwarded
- **Pinned by** `SandboxCompositionPinTest` (unit)

#### Scenario: .env.local is masked
- **WHEN** the project holds a `.env.local`
- **THEN** the container sees an empty read-only file and the temporary mask file is removed on exit
- **Pinned by** `SandboxCompositionPinTest` (unit), `SandboxSupervisorTest` (unit)

#### Scenario: No agent configuration variable crosses the boundary
- **WHEN** the container environment is composed
- **THEN** it carries no `DDR_ACP_AGENT_*` or key-bearing variable
- **Pinned by** `SandboxCompositionPinTest` (unit)
