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
  first. Permanent allow/deny decisions are remembered per session.
- **Persistence** — every session survives restarts: list, resume, or fully reload (with replay) past conversations, per
  project directory.
- **Client fs proxy** — when the IDE supports it, file reads and writes go through
  the editor (unsaved buffers are visible, changes show up as reviewable diffs).
- **MCP tool consumption** — tools from MCP servers configured in the IDE are
  bridged in, with untrusted behavior annotations honored only if you opt in.
- **Web fetch** — a built-in, prompt-free `web_fetch` tool retrieves HTTP(S) pages
  as line-numbered text (HTML converted, JSON/markdown passed through), paged like
  `read_file`. Binary payloads are refused loudly (download via `bash` instead),
  and private/loopback hosts are blocked unless you opt in with
  `ACP_WEB_FETCH_ALLOW_PRIVATE=1`.
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
export OPENROUTER_API_KEY="sk-or-..."
/path/to/acp-agent.kotlin/ddr-acp-agent-docker
```

The key never travels through the environment into the container: the launcher
stages it as a `0600` file and mounts it read-only at
`/tmp/openrouter-api-key`; the container env only carries
`OPENROUTER_API_KEY_FILE`. Alternatively provide `OPENROUTER_API_KEY_FILE`
pointing at your own `0600` file, which is mounted directly without staging.

On first use the launcher pulls `ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest`
(when offline it falls back to an already-pulled copy). To build the image locally
instead, run `./build-docker` and start with `--skip-pull`. The image runs
hardened: no capabilities, read-only rootfs, tmpfs home, non-root user matching
your host UID. Your project directory is bind-mounted, and session state is
persisted on the host so conversations survive container restarts.

### Direct

```bash
export OPENROUTER_API_KEY="sk-or-..."
/path/to/acp-agent.kotlin/ddr-acp-agent
```

The launcher rebuilds via Gradle when sources are newer than the installed binary (first run downloads dependencies),
then execs the agent.

## IDE setup

Configure the agent as a custom ACP agent in your client. The command is the
launcher above; the API key goes into the environment of the launched process.

JetBrains IDEs (ACP plugin) and Zed are known to work. Example JetBrains custom
agent configuration:

```json
"DdrAcpAgent": {
  "command": "/srv/git/github.com/dontdrinkandroot/acp-agent.kotlin/ddr-acp-agent-docker",
  "env": {
    "OPENROUTER_API_KEY": "sk-or-...",
    "FS_PROXY_ENABLED": 0
  }
}
```

Tips:

- `FS_PROXY_ENABLED: 0` disables the editor fs proxy (useful when the proxy
  misbehaves in your client); the agent then reads/writes files directly.
- The project working directory is whatever the IDE/editor passes as `cwd` —
  normally the opened project root.

## Configuration

All configuration is via environment variables.

| Variable                                     | Default                        | Description                                                                                              |
|----------------------------------------------|--------------------------------|----------------------------------------------------------------------------------------------------------|
| `OPENROUTER_API_KEY`                         | *(one of the two required)*    | OpenRouter API key. Never forwarded into the docker container; the launcher stages it as a `0600` file instead. |
| `OPENROUTER_API_KEY_FILE`                    | *(one of the two required)*    | File holding the OpenRouter API key (docker-secrets style). The docker launcher mounts it read-only at `/tmp/openrouter-api-key`. Direct runs read it at startup; setting both variants is an error. |
| `OPENROUTER_MODEL`                           | `openrouter/auto`              | Initial model; switchable per session.                                                                   |
| `OPENROUTER_BASE_URL`                        | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint (e.g. a proxy).                                                               |
| `OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED` | enabled                        | `0` disables automatic provider routing (throughput-sorted with a median price cap).                     |
| `FS_PROXY_ENABLED`                           | enabled                        | `0` forces local file access even when the client offers the fs proxy.                                   |
| `MCP_TRUST_ANNOTATIONS`                      | enabled                        | `0` treats all MCP tools as untrusted: always ask before running, even when the server claims read-only. |
| `ACP_BASH_TIMEOUT_SECONDS`                   | `600`                          | Shell command timeout (whole process tree is killed).                                                    |
| `ACP_MAX_TURN_REQUESTS`                      | `100`                          | Tool-calling LLM iterations per prompt before a final text-only summary.                                 |
| `ACP_WEB_FETCH_ALLOW_PRIVATE`                | blocked                        | `1` lets the `web_fetch` tool reach private/loopback hosts (blocked by default to prevent SSRF).         |
| `ACP_EXTRA_MOUNTS`                           | *(none)*                       | Comma-separated absolute paths treated as read-trusted: `read_file`/`list_dir`/`glob`/`grep` under them run without a permission prompt (writes still prompt). The docker launcher sets this automatically from the effective `ACP_DOCKER_EXTRA_MOUNTS`. |

Docker launcher extras (host side, not forwarded into the container):

| Variable                  | Default                         | Description                                                                                                              |
|---------------------------|---------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `ACP_DOCKER_STATE_DIR`    | `$XDG_STATE_HOME/ddr-acp-agent` | Host directory for session state. Absolute paths only. Useful when your home is fscrypt-encrypted (see Troubleshooting). |
| `ACP_DOCKER_HOME_VOLUME`  | *(tmpfs)*                       | Named Docker volume for the container home instead of a tmpfs.                                                           |
| `ACP_DOCKER_MOUNT_CACHES` | enabled                         | `0` disables sharing host tool caches (Gradle, uv, cargo, …) into the container.                                         |
| `ACP_DOCKER_EXTRA_MOUNTS` | *(none)*                        | Comma-separated host paths mounted at the identical in-container path, e.g. `/srv/data,/mnt/scratch:rw`. Default mode `ro`, suffix `:ro`/`:rw`; dirs and files; absolute host paths, must exist. Mounts inside the project dir or the container home are skipped (built-ins shadow extras; later, deeper mounts win). The effective extras are injected as `ACP_EXTRA_MOUNTS`, making them read-trusted for the agent. |
| `ACP_DOCKER_NETWORK`      | `development`                   | Docker network for the container.                                                                                        |
| `ACP_DOCKER_RW_ROOTFS`    | *(read-only)*                   | `1` leaves the container rootfs writable.                                                                                |
| `ACP_DOCKER_CAP_ADD`      | *(none)*                        | Comma-separated Linux capabilities to add back.                                                                          |
| `ACP_DOCKER_EXTRA_ARGS`   | *(none)*                        | Extra `docker run` arguments (space-separated).                                                                          |
| `DOCKER_BIN`              | auto                            | Absolute path to the Docker CLI.                                                                                         |

## Sessions

Session records live under `$XDG_STATE_HOME/ddr-acp-agent/sessions/`
(fallback `~/.local/state/ddr-acp-agent/sessions/`), one JSON file per session, and
are filtered by project directory in the client's session list. Use the client's
UI (or `session/list`, `session/load`, `session/resume`, `session/delete`) to pick
conversations back up after an IDE or agent restart; work in progress can also be
continued from the persisted plan.

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
"env": { "OPENROUTER_API_KEY": "sk-or-...", "ACP_DOCKER_STATE_DIR": "/srv/acp-agent-state" }
```

With the docker launcher, `OPENROUTER_API_KEY` in that `env` block only lives
in the launcher's environment; the key still reaches the container exclusively
via the read-only key file.

**`OPENROUTER_API_KEY` / `OPENROUTER_API_KEY_FILE must be set`** — the key was
not provided to the launched process (one of the two variants is required;
setting both is an error). Put it in the agent entry's `env` block of your IDE
configuration (not your shell profile), or export it before launching the
direct variant.

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
