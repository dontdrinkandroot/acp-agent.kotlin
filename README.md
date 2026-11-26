# acp-agent

A coding agent that plugs into AI-capable IDEs and editors over the
[Agent Client Protocol (ACP)](https://agentclientprotocol.com) — JSON-RPC 2.0 over
stdio, no GUI of its own. The IDE launches it as a subprocess; the agent reads and
edits your project, runs commands, and talks to LLMs via
[OpenRouter](https://openrouter.ai).

## Features

- **Plan / Build / Bash modes** — read-only exploration by default (`plan`); file
  writes unlocked in `build`; shell access in `bash`. The mode gates which tools the
  model may call, so a sandboxed "plan first, then ask for changes" workflow works
  out of the box.
- **Path-aware permissions** — everything inside the project working directory is
  fair game; anything outside it (reads and writes alike) and shell commands ask you
  first. Permanent allow/deny decisions are remembered per session. Reads and greps in
  the agent's scratch directory (`/tmp/ddr-acp-agent`, the `scratchDir` config key)
  never ask, in any mode. A repository can add its own directories in
  `.ai/ddr-acp-agent.json` without widening the global configuration (see
  [Project permission overlay](#project-permission-overlay)).
- **Persistence** — every session survives restarts: list, resume, or fully reload (with replay) past conversations, per
  project directory.
- **Client fs proxy** — when the IDE supports it, file reads and writes go through
  the editor (unsaved buffers are visible, changes show up as reviewable diffs).
- **MCP tool consumption** — tools from MCP servers configured in the IDE are
  bridged in, with untrusted behavior annotations honored only if you opt in.
- **Web fetch** — a built-in, prompt-free `web_fetch` tool retrieves HTTP(S) pages
  as line-numbered text (HTML converted, JSON/markdown passed through), paged like
  `read_file`. Binary payloads are refused loudly (download via `bash` instead),
  and non-public hosts (private ranges, loopback, CGNAT, IPv6 unique-local,
  multicast, ...) are blocked unless you opt in with
  `"tools": {"webFetch": {"allowPrivate": true}}`.
- **Run configurations** — project-defined commands in `.ai/run.json` (also surfaced to the
  model as `run` tool configs) execute prompt-free in every mode; the model's `args` array is
  passed to the command as positional parameters at the `[args]` slot — one element is one
  argument, never shell-interpreted.
- **Bounded output & budgets** — tool output is capped (no context explosions),
  shell commands are killed after a configurable timeout, and tool-calling turns are
  capped with a final synthesis pass.
- **`/info` slash command** — prints the configuration the agent actually resolved (version/build, session
  mode/cwd/model, reasoning and provider, behaviour switches,
  trusted paths incl. the API key *file*). Answered locally: no model call, no tokens,
  and it never enters the conversation.

## Requirements

- **Docker variant (recommended)**: Docker, a JDK 25 and an OpenRouter API key. The
  image ships the agent runtime and its toolchain, but the sandbox entry point builds
  the JVM distribution from this checkout and runs the supervisor locally - so the
  host needs `git` and the Gradle wrapper's JDK 25, and the IDE's `PATH` must carry
  `java` and `docker` (the wrapper and the supervisor both resolve them from `PATH`).
  Run `./bin/acp-agent-sandbox` once from a shell so the first IDE launch does not
  sit through a cold build.
- **Direct variant**: JDK 25 (both entry points build the distribution via the Gradle
  wrapper first, so a JRE is not enough) and an OpenRouter API key.

## Quick start

### Docker

```bash
mkdir -p ~/.config/ddr-acp-agent
printf '%s\n' "sk-or-..." >~/.config/ddr-acp-agent/openrouter.key
chmod 600 ~/.config/ddr-acp-agent/openrouter.key
/path/to/acp-agent/bin/acp-agent-sandbox
```

The key never travels through the environment. It lives in your config
directory (default `~/.config/ddr-acp-agent`), which the sandbox mounts
read-only at the container's own `$XDG_CONFIG_HOME/ddr-acp-agent` — so
the same `config.json`/`openrouter.key` work for the direct and the sandboxed
variant. The supervisor has no key logic: the agent resolves the file, the
supervisor only mounts the directory.

The sandbox entry point builds the JVM distribution, then makes sure
`ghcr.io/dontdrinkandroot/acp-agent:latest` exists: it runs `docker image inspect`
and builds the image locally via `./build-docker` when it is missing (or when you
pass `--rebuild`). It **never pulls** — update the upstream image with a manual
`docker pull ghcr.io/dontdrinkandroot/acp-agent:latest` before starting; the
inspect then succeeds and the build is skipped. A profile needs the image to be
built from a release that reads `--profile` (one `--rebuild` after upgrading).
The image runs
hardened: no capabilities, read-only rootfs, tmpfs home, non-root user matching
your host UID. The home and Gradle tmpfs mounts are exec-allowed because
toolchains execute native binaries extracted into them (AAPT2 from Maven,
Kotlin/Native). Your project directory — the directory the IDE launches the agent
from, which it also passes as the session `cwd` (nothing enforces that identity,
so launch the agent from the project root) — is bind-mounted at the identical
path and used as the container's workdir, and session state is
persisted on the host so conversations survive container restarts.

Your host Android SDK is shared into the container automatically: when
`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) is set it must be an existing absolute
directory (anything else aborts the supervisor) and is mounted read-write at the
identical path with both variables pinned to it; otherwise an existing
`$HOME/Android/Sdk` is picked up and mounted at
`/home/dev/Android/Sdk`. Either way both variables point at the mounted path, so
Gradle builds, `sdkmanager`, `adb` and similar tools work without further setup. Notes:

- Android builds resolve the SDK location in this order: `local.properties`
  (`sdk.dir`) first, then the `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment
  variables (per AGP's `SdkLocator`). A `local.properties` in the project
  pointing at a host-only path therefore overrides the container mount and
  breaks container runs — delete it (or point it at the container path). The
  supervisor always pins both variables to the same path, satisfying AGP's
  newer check that they must match when both are set.
- No emulator/AVDs, KVM or USB devices: the container cannot run emulators;
  `connectedAndroidTest` and `adb devices` see no devices.

### Reaching MCP servers on the host

The container runs in the **host network namespace by default** (`--network host`) and
`host.docker.internal` is mapped to `127.0.0.1`, so MCP servers running on the
host are reachable from the container at that name — e.g. the PhpStorm MCP
server:

```json
{
  "mcpServers": {
    "phpstorm": {
      "type": "sse",
      "url": "http://host.docker.internal:64442/sse",
      "headers": {
        "IJ_MCP_SERVER_PROJECT_PATH": "/srv/git/gitlab.com/sorst/philip/sdrnr.net"
      }
    }
  }
}
```

(Type and header shape above are as the IDE presents them; over ACP the agent
receives this translated into the protocol's `{name, url, headers: [{name,
value}]}` SSE form.)

### Direct

```bash
# The key lives in a 0600 file, never in the environment.
mkdir -p ~/.config/ddr-acp-agent
printf '%s\n' "sk-or-..." >~/.config/ddr-acp-agent/openrouter.key
chmod 600 ~/.config/ddr-acp-agent/openrouter.key
/path/to/acp-agent/bin/acp-agent
```

The entry point always runs `installDist` (the first run downloads dependencies),
then execs the agent from `build/install/acp-agent/bin/acp-agent`.

## IDE setup

Configure the agent as a custom ACP agent in your client. The command is one of the
two entry points above; the API key is read from a key file (see Configuration /
API key), never from the environment. To use a profile, append
`--profile <name>` (the sandbox entry point forwards it into the container).

JetBrains IDEs (ACP plugin) and Zed are known to work. Example JetBrains custom
agent configuration:

```json
"DdrAcpAgent": {
  "command": "/srv/git/github.com/dontdrinkandroot/acp-agent.kotlin/bin/acp-agent-sandbox"
}
```

Tips:

- The editor fs proxy is on by default whenever the client offers it: reads and
  writes go through the editor (unsaved buffers are visible) and the client
  renders `write_file`/`edit_file` changes itself, so the agent skips its own
  diff blocks for those two. `"tools": {"fsProxy": false}` in `config.json`
  disables the proxy (e.g. when it misbehaves in your client); the agent then reads/writes
  files directly and emits whole-file diffs for every mutation — with the
  baseline read from disk, which can disagree with unsaved editor buffers.
  Move/delete change evidence is unaffected either way: those tools never go
  through the proxy and always report their diffs.
- The project working directory is whatever the IDE/editor passes as `cwd` —
  normally the opened project root. It must be an existing directory: a session
  whose `cwd` is missing or is not a directory is refused instead of running
  tools against the wrong tree.
- stdio MCP servers declared in the session start **in that project directory**
  and only see a small, fixed set of environment variables (`PATH`, `HOME`,
  `TMPDIR`, locale, `TERM`, `TZ`, `USER`). Anything else a server needs — a token,
  a project path — belongs in its own `env` entry in the MCP server
  configuration; secrets from the agent's environment are deliberately not
  inherited.

## Configuration

The agent is configured **exclusively through files**: built-in defaults,
overridden by the selected `config.json` and then by an optional project overlay
(`<project>/.ai/ddr-acp-agent.json`). No environment variable carries an agent
setting (a `DDR_ACP_AGENT_*` variable is deliberately inert and produces no
warning). The files are read once at startup — change them, then
restart/relaunch the agent.

### Config file

`$XDG_CONFIG_HOME/ddr-acp-agent/config.json` (fallback `~/.config/ddr-acp-agent/config.json`).

**Profiles:** launch the agent with `--profile <name>` to select `<name>.json`
in the same directory (e.g. `--profile work` reads `work.json`) — a bare profile
name, no path, so it resolves identically on the host and inside the docker
container. A `.json`-suffixed value is rejected (the suffix is added by the
agent), a selected profile must exist, and a missing default file simply means
"all defaults". The argument parser is strict: any other argument ends startup
with an error naming it, so a mistyped `--profil work` can never silently run on
the default profile.

```json
{
  "openRouter": {
    "apiKeyFile": "/home/me/.config/ddr-acp-agent/openrouter.key",
    "defaultModel": "openrouter/auto",
    "baseUrl": "https://openrouter.ai/api/v1",
    "autoThroughputSorting": true
  },
  "tools": {
    "maxTurnRequests": 100,
    "fsProxy": true,
    "bash": {
      "timeoutSeconds": 600
    },
    "webFetch": {
      "allowPrivate": false
    }
  },
  "mcp": {
    "trustAnnotations": true
  },
  "permissions": {
    "readableDirectories": ["/srv/git"],
    "writableDirectories": ["/srv/git/github.com/me/scratch"],
    "unlistedPaths": "prompt"
  },
  "scratchDir": "/tmp/ddr-acp-agent"
}
```

Every section and key is optional; absent means the default. Unknown keys are
ignored (a newer file still works with an older agent). A wrong type or an
invalid value fails at startup with the JSON path, e.g.
`config file '…/config.json': tools.bash.timeoutSeconds must be between 1 and 3600, got -5`.

### Behaviour switches

Every key above is documented in the example — the most important ones:

| Key                                   | Default                        | Description                                                                                                             |
|---------------------------------------|--------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `openRouter.defaultModel`             | `openrouter/auto`              | Initial model; switchable per session.                                                                                  |
| `openRouter.baseUrl`                  | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint (e.g. a proxy).                                                                              |
| `tools.maxTurnRequests`               | `100`                          | Tool-calling LLM iterations per prompt before a final text-only summary (>= 1).                                         |
| `tools.fsProxy`                       | `true`                         | `false` forces local file access even when the client offers the fs proxy.                                              |
| `tools.bash.timeoutSeconds`           | `600`                          | Shell command timeout, 1-3600 seconds (whole process tree is killed).                                                   |
| `tools.webFetch.allowPrivate`         | `false`                        | `true` lets `web_fetch` reach private/loopback/CGNAT/ULA hosts (non-public addresses are blocked by default to prevent SSRF). |
| `mcp.trustAnnotations`                | `true`                         | `false` treats all MCP tools as untrusted: always ask, even when the server claims read-only.                           |
| `permissions.readableDirectories`     | *(none)*                       | Absolute paths (never `/`): the read-only path tools run there without a prompt. Under the docker sandbox this list **is** the read-only mount list. A project can add entries via `.ai/ddr-acp-agent.json`. |
| `permissions.writableDirectories`     | *(none)*                       | Absolute paths (never `/`): the mutating path tools run there without a prompt (implies readable). Under the docker sandbox this list **is** the read-write mount list. A project can add entries via `.ai/ddr-acp-agent.json`. |
| `permissions.unlistedPaths`           | `prompt`                       | `prompt` asks via `session/request_permission`; `deny` refuses outright, naming the config entry that would have covered the path. |
| `scratchDir`                          | `/tmp/ddr-acp-agent`           | The agent's own scratch directory, read-trusted like a readable directory. Must be absolute and not `/`; created `0700` and verified at session start. Never cleaned up. |

### Inspecting the effective configuration (`/info`)

Type `/info` in a chat turn — the agent advertises it as a slash command, so the
IDE offers it in the command list and autocompletion — to print the
configuration it actually resolved: agent version and build, the session's cwd,
mode and model, the effective reasoning/provider selection, the behaviour
switches, and the trusted paths including the API key **file** (never the key
itself) and the project overlay that was applied (`(none)` when the launch
directory has none).

The command is answered locally: it spends no tokens, does not call the model
and does not become part of the conversation (not stored in the session record,
not replayed when the session is loaded). Everything after the command word is
ignored and the reply says so; `/infofoo` is an ordinary prompt.

Because the configuration is resolved once at startup (see above), `/info`
shows the values from when the agent was launched — restart the agent after
changing `config.json`.

### API key

The key is **only ever read from a file**, never from a variable holding the key
itself (a key in the environment leaks into every child process, `env` dumps and
`ps` output). Resolution order:

1. `openRouter.apiKeyFile` from `config.json`
2. `<config dir>/openrouter.key`

If none resolves, startup fails with the tried sources. **Every key file must be
owner-only (`0600` or `0400`)** — a group- or world-readable file is refused with
its path.

### Directory permissions

The session working directory is implicitly readable **and** writable.
Additionally, `readableDirectories` covers the read-only path tools (`read_file`/`list_dir`/`glob`/`grep`) and
`writableDirectories` covers the
mutating ones (`write_file`/`edit_file`/`move_*`/`delete_*`, also readable).
Paths outside the cwd and outside every list follow `unlistedPaths`: `prompt`
(ask) or `deny` (hard refusal, fix the config lists instead).
`bash`, `run` and MCP tools keep their own permission rules.

**Docker sandbox:** the two lists *are* the mount list — a readable directory is
mounted read-only, a writable one read-write, at the identical in-container path, so
the agent trusts exactly what is mounted. The supervisor reads the same files
(`config.json` plus the project overlay) and composes the mounts from the merged
`permissions.*`; a configured directory that does not exist aborts the sandbox
(docker cannot mount it).

### Project permission overlay

A repository can add its own permission directories without touching the global
lists: `<project>/.ai/ddr-acp-agent.json` next to the checkout, read from the
directory the agent is launched in (the same directory `.ai/run.json` lives in).

```json
{
  "permissions": {
    "readableDirectories": ["/srv/data/shared-fixtures"],
    "writableDirectories": ["/srv/build-cache"],
    "unlistedPaths": "prompt"
  }
}
```

* **Only `permissions.*` may be set here** — `readableDirectories`,
  `writableDirectories`, `unlistedPaths`. Every other key (tool settings, `mcp.*`,
  `openRouter.*`, `scratchDir`, `sandbox`) belongs in `config.json` and is refused
  with a message saying so. The file is parsed **strictly at every level**: unlike
  `config.json`, an unknown key aborts startup instead of being ignored — a grant
  file that silently grants nothing is worse than a failing one.
* **Add-only merge:** the directory lists are unioned with the global lists (a
  project can add entries, never remove one); `unlistedPaths` replaces the global
  value in either direction.
* **Absolute paths only, never `/`**, and every named directory must exist — a
  missing directory fails startup naming the path. (The global lists only warn
  about a missing directory, because they may name mounts of another machine; this
  file is part of the checkout.)
* **Merged entries look like global entries**: prompt-free access, the system
  prompt's trusted-paths section and — under the docker sandbox — the mount list
  all come from the same merged lists. `/info` names the project file when one was
  applied.
* **The file is off-limits to the file tools** (`read_file`, `edit_file`,
  `write_file`, `delete_file` refuse it, `list_dir`/`glob`/`grep` hide it) exactly
  like `.ai/run.json`: a grant file the agent could rewrite is not a boundary.
* **Read once at startup from the launch directory.** A session opened on a
  different directory than the agent was launched in runs under the launch
  directory's overlay; that project's own overlay is not consulted.

### Sandbox settings (`sandbox` group)

The sandbox supervisor reads the `sandbox` group of the same `config.json` (the
agent ignores it; the project overlay cannot set it). Every key is optional;
absent means the default.

| Key                 | Default                         | Description                                                                                                                                                                                                                                                                                                                                                        |
|---------------------|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `network`           | `host`                          | Docker network. The default `host` shares the host network namespace: this is what makes Gradle's cross-process cache locking work (the lock handover is a loopback UDP ping that does not cross network namespaces, so a bridge-networked container fails with `Timeout waiting to lock` after ~60s), and it lets the container reach host loopback services. Set e.g. `development` to restore isolation — compose service names then resolve again through Docker's embedded DNS. |
| `stateDir`          | `$XDG_STATE_HOME/ddr-acp-agent` | Host directory for session state (mounted at the container's `~/.local/state/ddr-acp-agent`). Absolute path. Useful when your home is fscrypt-encrypted (see Troubleshooting).                                                                                                                                                                                     |
| `mountCaches`       | `true`                          | `false` disables sharing host tool caches (uv, cargo, …) into the container. Gradle is special: only its content-addressed cache leaves are shared rw - and only while this is `true` - never the whole `~/.gradle`; the daemon registry/logs and per-version state live in a container-private `.gradle` tmpfs so container and host Gradle daemons never interfere. |
| `homeVolume`        | *(tmpfs)*                       | Named Docker volume for the container home instead of a tmpfs.                                                                                                                                                                                                                                                                                                      |
| `extraArgs`         | `[]`                            | Additional `docker run` arguments (a JSON array of literal tokens, never word-split).                                                                                                                                                                                                                                                                                |
| `capAdd`            | `[]`                            | Linux capabilities to add back (a JSON array, e.g. `["SYS_PTRACE"]`).                                                                                                                                                                                                                                                                                                |
| `rwRootfs`          | `false`                         | `true` leaves the container rootfs writable.                                                                                                                                                                                                                                                                                                                        |

Host environment variables that are a *tool's* own configuration still apply and are
shared into the container: `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `UV_CACHE_DIR`,
`PIP_CACHE_DIR`, `COMPOSER_CACHE_DIR`, `YARN_CACHE_FOLDER`, `HF_HOME`,
`NPM_CONFIG_CACHE`, `CARGO_HOME`, `GOMODCACHE`, `NUGET_PACKAGES`, `PUB_CACHE`,
`PNPM_CONFIG_STORE_DIR`/`PNPM_CONFIG_CACHE_DIR`/`PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE`,
`KONAN_DATA_DIR`, `GRADLE_USER_HOME`, `UV_LINK_MODE`, plus `TZ` and the git identity.
Docker itself comes from `PATH` (see Troubleshooting). `scratchDir` keeps its
sandbox semantics: it is a *container* path, and the container's `/tmp` is a private
tmpfs, so scratch content is per-container-session.

### Migrating from `ddr-acp-agent-docker`

The bash launcher is gone: `ddr-acp-agent` and `ddr-acp-agent-docker` no longer
exist, and the agent's configuration is file-only — no `DDR_ACP_AGENT_*` variable
is read any more (a leftover is silently inert, so do not rely on it), and the
`--skip-pull` flag was removed outright (a strict argument parser now rejects it
with exit code 2).

| Old | New |
|---|---|
| `command: …/ddr-acp-agent-docker` | `command: …/bin/acp-agent-sandbox` |
| `command: …/ddr-acp-agent` | `command: …/bin/acp-agent` |
| `args: ["--skip-pull"]` | remove — the sandbox entry point checks `docker image inspect` and builds locally; it never pulls |
| `DDR_ACP_AGENT_CONFIG_FILE=work.json` | `args: ["--profile", "work"]` (reads `work.json`; a `.json` suffix is rejected) |
| `DDR_ACP_AGENT_DOCKER_*` (`STATE_DIR`, `HOME_VOLUME`, `MOUNT_CACHES`, `NETWORK`, `RW_ROOTFS`, `CAP_ADD`, `EXTRA_ARGS`) | the `sandbox` group above (`stateDir`, `homeVolume`, `mountCaches`, `network`, `rwRootfs`, `capAdd`, `extraArgs`) |
| `DDR_ACP_AGENT_TOOLS_FS_PROXY` & friends | the matching `config.json` keys (`tools.fsProxy`, …) |
| `DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES` / `…_WRITABLE_DIRECTORIES` | `permissions.readableDirectories` / `writableDirectories` — one list drives the mounts and the agent's trust |
| `DDR_ACP_AGENT_SCRATCH_DIR` | `scratchDir` |
| `DOCKER_BIN` | remove — docker is resolved from `PATH` |
| image `ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest` | `ghcr.io/dontdrinkandroot/acp-agent:latest` (`docker pull` once) |
| `ACP_DOCKER_*` (the older spellings) | dead, as before |

`XDG_CONFIG_HOME`/`XDG_STATE_HOME`/`TZ`/`HOME` stay environment variables — they
are standard host facts, not agent config. The option values in `config.json` are
**JSON types** now: `"fsProxy": false`, not `"0"` (a wrong type fails at startup
with the JSON path). A trailing slash in a permission list entry is normalized
away, so `/srv/git/` and `/srv/git` name the same mount.

Under the sandbox the API key must sit in the mounted config directory
(`<config dir>/openrouter.key`, `0600`): `openRouter.apiKeyFile` is resolved
*inside* the container, so a host path there is unreadable.

## Sessions

Session records live under `$XDG_STATE_HOME/ddr-acp-agent/sessions/`
(fallback `~/.local/state/ddr-acp-agent/sessions/`), one JSON file per session, and
are filtered by project directory in the client's session list; sessions currently open
in the running agent are marked via the `_meta` field (`net.dontdrinkandroot/active`).
Use the client's UI (or `session/list`, `session/load`, `session/resume`, `session/close`,
`session/delete`) to pick conversations back up after an IDE or agent restart; work in
progress can also be continued from the persisted plan.

## Troubleshooting

**Sessions list stays empty / `Required key not available` in stderr logs.**
Your project or home directory is on an fscrypt-encrypted filesystem with a
v1 policy (e.g. systemd-homed fscrypt homes). Containers cannot access the
kernel keyring holding the decryption key, so the agent cannot write its session
records to `$XDG_STATE_HOME`. Point the host-side state directory somewhere
unencrypted:

```bash
sudo mkdir -p /srv/acp-agent-state && sudo chown "$USER" /srv/acp-agent-state
```

```json
"sandbox": { "stateDir": "/srv/acp-agent-state" }
```

**`No OpenRouter API key configured`** — no key resolved. The error lists the
tried sources: `openRouter.apiKeyFile` in `config.json`, and the default
`$XDG_CONFIG_HOME/ddr-acp-agent/openrouter.key`. Create that file with mode
`0600`. Under the docker sandbox the config directory is mounted read-only, so
the key must live there; a key file outside it is not mounted and therefore
unreadable inside the container.

**`API key file … must be owner-only`** — the key file is readable by group or
others. Run `chmod 600 <file>`.

**Agent produces no output / connection drops immediately.** The agent speaks
NDJSON on stdout and logs to stderr; if a wrapper script or environment writes to
stdout, the protocol breaks. Use the provided entry points as-is.

**Docker: image missing / stale.** The sandbox entry point never pulls: update with
`docker pull ghcr.io/dontdrinkandroot/acp-agent:latest`, or build locally with
`./build-docker` (or pass `--rebuild`).

**`docker CLI not found on PATH`.** The wrappers, `build-docker` and the supervisor
all resolve docker from `PATH` — launch the IDE from a session where docker is on
`PATH` (or fix the IDE's environment).

## Building from source

```bash
./gradlew build       # compile + unit tests + black-box e2e suite
./gradlew installDist # produces build/install/acp-agent/bin/{acp-agent,acp-agent-sandbox}
./build-docker        # build the container image locally
```

Requires JDK 25. See [AGENTS.md](AGENTS.md) for the architecture, conventions and
the full development guide.
