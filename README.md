# acp-agent.kotlin

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
  the agent's scratch directory (`/tmp/ddr-acp-agent`, override with
  `DDR_ACP_AGENT_SCRATCH_DIR`) never ask, in any mode.
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
  `DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE=true`.
- **Run configurations** — project-defined commands in `.ai/run.json` (also surfaced to the
  model as `run` tool configs) execute prompt-free in every mode; the model's `args` array is
  passed to the command as positional parameters at the `[args]` slot — one element is one
  argument, never shell-interpreted.
- **Bounded output & budgets** — tool output is capped (no context explosions),
  shell commands are killed after a configurable timeout, and tool-calling turns are
  capped with a final synthesis pass.

## Requirements

- **Docker variant (recommended)**: Docker and an OpenRouter API key. Everything
  else ships in the image.
- **Direct variant**: Java 25 (JRE is enough) and an OpenRouter API key.

## Quick start

### Docker

```bash
mkdir -p ~/.config/ddr-acp-agent
printf '%s\n' "sk-or-..." >~/.config/ddr-acp-agent/openrouter.key
chmod 600 ~/.config/ddr-acp-agent/openrouter.key
/path/to/acp-agent.kotlin/ddr-acp-agent-docker
```

The key never travels through the environment. It lives in your config
directory (default `~/.config/ddr-acp-agent`), which the launcher mounts
read-only at the container's own config directory (same `XDG_CONFIG_HOME`) — so
the same `config.json`/`openrouter.key` work for the direct and the docker
variant. The launcher has no key logic at all: the old
`OPENROUTER_API_KEY` / `OPENROUTER_API_KEY_FILE` variables are no longer read.

On first use the launcher pulls `ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest`
(when offline it falls back to an already-pulled copy). To build the image locally
instead, run `./build-docker` and start with `--skip-pull`. The image runs
hardened: no capabilities, read-only rootfs, tmpfs home, non-root user matching
your host UID. The home and Gradle tmpfs mounts are exec-allowed because
toolchains execute native binaries extracted into them (AAPT2 from Maven,
Kotlin/Native). Your project directory is bind-mounted, and session state is
persisted on the host so conversations survive container restarts.

Your host Android SDK is shared into the container automatically: when
`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) is set it must be an existing absolute
directory (anything else aborts the launcher), otherwise an existing
`$HOME/Android/Sdk` is picked up. It is mounted read-write at
`/home/dev/Android/Sdk` with both variables pinned to that path, so Gradle
builds, `sdkmanager`, `adb` and similar tools work without further setup. Notes:

- Android builds resolve the SDK location in this order: `local.properties`
  (`sdk.dir`) first, then the `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment
  variables (per AGP's `SdkLocator`). A `local.properties` in the project
  pointing at a host-only path therefore overrides the container mount and
  breaks container runs — delete it (or point it at the container path). The
  launcher always pins both variables to the same path, satisfying AGP's
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
/path/to/acp-agent.kotlin/ddr-acp-agent
```

The launcher rebuilds via Gradle when sources are newer than the installed binary (first run downloads dependencies),
then execs the agent.

## IDE setup

Configure the agent as a custom ACP agent in your client. The command is the
launcher above; the API key is read from a key file (see Configuration / API
key), never from the environment.

JetBrains IDEs (ACP plugin) and Zed are known to work. Example JetBrains custom
agent configuration:

```json
"DdrAcpAgent": {
  "command": "/srv/git/github.com/dontdrinkandroot/acp-agent.kotlin/ddr-acp-agent-docker"
}
```

Tips:

- The editor fs proxy is on by default whenever the client offers it: reads and
  writes go through the editor (unsaved buffers are visible) and the client
  renders `write_file`/`edit_file` changes itself, so the agent skips its own
  diff blocks for those two. `DDR_ACP_AGENT_TOOLS_FS_PROXY: "0"` disables the
  proxy (e.g. when it misbehaves in your client); the agent then reads/writes
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

Configuration is layered: **built-in defaults < `config.json` < environment**.
The file is read once at startup (change it, then restart/relaunch the agent);
environment variables always win.

### Config file

`$XDG_CONFIG_HOME/ddr-acp-agent/config.json` (fallback `~/.config/ddr-acp-agent/config.json`).
`DDR_ACP_AGENT_CONFIG_FILE` selects a **different file name in the same directory**
(e.g. `work.json`) — a bare file name, no path, so the same value resolves
identically on the host and inside the docker container. A selected file must
exist, while a missing default file simply means "all defaults".

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

### Environment overrides

Every key has a derived env variable: the path in UPPER_SNAKE, prefixed with
`DDR_ACP_AGENT_`. An unset variable means "no override".

| Env variable                                        | Default                        | Description                                                                                                                                                                  |
|-----------------------------------------------------|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE`            | *(see API key)*                | File holding the API key.                                                                                                                                                    |
| `DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL`           | `openrouter/auto`              | Initial model; switchable per session.                                                                                                                                       |
| `DDR_ACP_AGENT_OPEN_ROUTER_BASE_URL`                | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint (e.g. a proxy).                                                                                                                                   |
| `DDR_ACP_AGENT_OPEN_ROUTER_AUTO_THROUGHPUT_SORTING` | `true`                         | `false` disables automatic provider routing (throughput-sorted with a median price cap).                                                                                     |
| `DDR_ACP_AGENT_TOOLS_FS_PROXY`                      | `true`                         | `false` forces local file access even when the client offers the fs proxy.                                                                                                   |
| `DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS`             | `100`                          | Tool-calling LLM iterations per prompt before a final text-only summary (>= 1).                                                                                              |
| `DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS`          | `600`                          | Shell command timeout, 1-3600 seconds (whole process tree is killed).                                                                                                        |
| `DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE`       | `false`                        | `true` lets `web_fetch` reach private/loopback/CGNAT/ULA hosts (non-public addresses are blocked by default to prevent SSRF).                                                |
| `DDR_ACP_AGENT_MCP_TRUST_ANNOTATIONS`               | `true`                         | `false` treats all MCP tools as untrusted: always ask, even when the server claims read-only.                                                                                |
| `DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES`    | *(none)*                       | Comma-separated absolute paths: the read-only path tools run there without a prompt. Under the docker launcher this list **is** the read-only mount list.                    |
| `DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES`    | *(none)*                       | Comma-separated absolute paths: the mutating path tools run there without a prompt (implies readable). Under the docker launcher this list **is** the read-write mount list. |
| `DDR_ACP_AGENT_PERMISSIONS_UNLISTED_PATHS`          | `prompt`                       | `prompt` asks via `session/request_permission`; `deny` refuses outright, naming the config entry that would have covered the path.                                           |
| `DDR_ACP_AGENT_SCRATCH_DIR`                         | `/tmp/ddr-acp-agent`           | The agent's own scratch directory, read-trusted like a readable directory. Must be absolute and not `/`; created `0700` and verified at session start. Never cleaned up.     |
| `DDR_ACP_AGENT_CONFIG_FILE`                         | `config.json`                  | File name inside the config directory (a "profile"), e.g. `work.json`. Bare name only — no path.                                                                             |

Booleans accept `1/true/yes/on` and `0/false/no/off` (case-insensitive);
anything else is a startup error. Numbers are strict — an out-of-range value is
an error, never silently clamped.

### API key

The key is **only ever read from a file**, never from a variable holding the key
itself (a key in the environment leaks into every child process, `env` dumps and
`ps` output). Resolution order:

1. `DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE`
2. `openRouter.apiKeyFile` from `config.json`
3. `<config dir>/openrouter.key`

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

**Docker:** the two lists *are* the mount list — a readable directory is mounted
read-only, a writable one read-write, at the identical in-container path. Set
them as environment variables (`DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES`,
`…_WRITABLE_DIRECTORIES`), because only the launcher can mount host paths. The
`permissions.*` entries in `config.json` are therefore a **direct-run** concern:
the env layer overrides the file layer, so a host-only path is never trusted
inside the container without being mounted.

Docker launcher extras (host side, not forwarded into the container):

The launcher's own settings follow the same naming scheme as the agent config (`DDR_ACP_AGENT_DOCKER_*`) but are
deliberately **not** forwarded into the
container — they configure the `docker run` call, not the agent.

| Variable                            | Default                         | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
|-------------------------------------|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DDR_ACP_AGENT_DOCKER_STATE_DIR`    | `$XDG_STATE_HOME/ddr-acp-agent` | Host directory for session state. Absolute paths only. Useful when your home is fscrypt-encrypted (see Troubleshooting).                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `DDR_ACP_AGENT_DOCKER_HOME_VOLUME`  | *(tmpfs)*                       | Named Docker volume for the container home instead of a tmpfs.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `DDR_ACP_AGENT_DOCKER_MOUNT_CACHES` | enabled                         | `0` disables sharing host tool caches (uv, cargo, …) into the container. Gradle is special: only the content-addressed cache leaves (`caches/modules-2`, `caches/jars-*`, `wrapper/dists`, `jdks`) are shared rw, never the whole `~/.gradle` — the daemon registry/logs and per-version state live in a container-private `.gradle` tmpfs so container and host Gradle daemons never interfere. The home and Gradle tmpfs mounts are exec-allowed (toolchains execute binaries extracted into them, e.g. AAPT2, Kotlin/Native).                              |
| `DDR_ACP_AGENT_DOCKER_NETWORK`      | `host`                          | Docker network for the container. The default `host` shares the host network namespace: this is what makes Gradle's cross-process cache locking work (the lock handover is a loopback UDP ping that does not cross network namespaces, so a bridge-networked container fails with `Timeout waiting to lock` after ~60s), and it lets the container reach host loopback services. Set e.g. `development` to restore network isolation — compose service names (`postgres`) then resolve again through Docker's embedded DNS, at the cost of the lock timeouts. |
| `DDR_ACP_AGENT_DOCKER_RW_ROOTFS`    | *(read-only)*                   | `1` leaves the container rootfs writable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `ANDROID_HOME`                      | *(auto)*                        | Host Android SDK directory shared into the container (`ANDROID_SDK_ROOT` is used when `ANDROID_HOME` is unset; first one set wins). An existing `$HOME/Android/Sdk` is picked up without either variable. Set but unusable values (relative path, `/`, missing directory) abort the launcher.                                                                                                                                                                                                                                                                 |
| `ANDROID_SDK_ROOT`                  | *(auto)*                        | See `ANDROID_HOME`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `DDR_ACP_AGENT_DOCKER_CAP_ADD`      | *(none)*                        | Comma-separated Linux capabilities to add back.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `DDR_ACP_AGENT_DOCKER_EXTRA_ARGS`   | *(none)*                        | Extra `docker run` arguments (space-separated).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `DOCKER_BIN`                        | auto                            | Absolute path to the Docker CLI.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

The previous `ACP_DOCKER_*` spellings are **no longer read** — rename them.

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
"env": { "DDR_ACP_AGENT_DOCKER_STATE_DIR": "/srv/acp-agent-state" }
```

**`No OpenRouter API key configured`** — no key resolved. The error lists the
tried sources: `DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE`, `openRouter.apiKeyFile`
in `config.json`, and the default `$XDG_CONFIG_HOME/ddr-acp-agent/openrouter.key`.
Create that file with mode `0600`. Under the docker launcher the config directory
is mounted read-only, so the key must live there; a key file outside it is not
mounted and therefore unreadable inside the container.

**`API key file … must be owner-only`** — the key file is readable by group or
others. Run `chmod 600 <file>`.

**Agent produces no output / connection drops immediately.** The agent speaks
NDJSON on stdout and logs to stderr; if a wrapper script or environment writes to
stdout, the protocol breaks. Use the provided launchers as-is.

**Docker: `docker pull` fails.** Offline? The launcher continues with an existing
local image if there is one; otherwise build it locally with `./build-docker`.

## Building from source

```bash
./gradlew build       # compile + unit tests + black-box e2e suite
./gradlew installDist # produces build/install/acp-agent.kotlin/bin/acp-agent.kotlin
./build-docker        # build the container image locally
```

Requires JDK 25. See [AGENTS.md](AGENTS.md) for the architecture, conventions and
the full development guide.
