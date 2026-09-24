# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

`acp-agent.kotlin` is a coding agent that talks to JetBrains IDEs (IntelliJ IDEA,
PhpStorm, etc.) exclusively over the **Agent Client Protocol (ACP)** - a JSON-RPC 2.0 /
NDJSON protocol over stdio. There is **no GUI/TUI**; the agent is launched by the IDE as a
subprocess and driven via `stdin`/`stdout`.

The agent acts strictly as an ACP **agent**: it only *consumes* ACP, never acts as a
client/IDE, and never exposes an MCP server - it only *consumes* MCP servers.

## Tech stack

JVM-only application in a single module (`kotlin("jvm")` + `application`). Main sources in
`src/main/kotlin`, tests in `src/test/kotlin`.

| Layer                  | Dependency                                                                            | Version                           |
|------------------------|---------------------------------------------------------------------------------------|-----------------------------------|
| Kotlin / plugins       | `kotlin("jvm")`, `kotlin("plugin.serialization")`, `application` (JDK 25, Gradle 9.6) | 2.4.10                            |
| Dep update check       | `io.github.ben-manes.versions.settings` (in `settings.gradle.kts`; `dependencyUpdates`) | 0.61.0                            |
| ACP                    | `com.agentclientprotocol:acp`                                                         | 0.30.1                            |
| MCP                    | `io.modelcontextprotocol:kotlin-sdk-client`                                           | 0.15.0                            |
| HTTP                   | ktor client (CIO engine)                                                              | 3.5.1                             |
|                        | ktor-client-encoding (gzip/deflate for `web_fetch`)                                   | 3.5.1                             |
| HTML parsing           | jsoup (HTML -> line-based text for `web_fetch`; zero runtime deps)                    | 1.23.2                            |
| Coroutines             | kotlinx-coroutines-core                                                               | 1.11.0 (resolved)                 |
| IO                     | kotlinx-io-core                                                                       | 0.9.1                             |
| JSON                   | kotlinx-serialization-json                                                            | 1.11.0 (resolved; declared 1.9.0) |
| OpenRouter wire models | ai.koog:prompt-executor-openrouter-client (models only, no framework)                 | 1.2.0                             |
| UUIDv7 ids             | com.github.f4b6a3:uuid-creator (zero deps; `UuidCreator.getTimeOrderedEpoch()`)        | 6.1.1                             |
| Logging                | kotlin-logging via slf4j-simple                                                       | 8.0.4 / 2.0.17                    |

Gradle conflict resolution overrides our declared kotlinx versions (Koog forces the
resolved ones); that is expected. Koog is used as a wire-model / serialization library
only (no framework); its OpenAI-compat wire types are the internal LLM contract (see the
LLM provider bullet below). kotlin-logging 8.0.4 is declared explicitly because
Koog/ktor/ACP pull 7.0.0 and 8.0.01 transitives; pinning 8.0.4 pins the
banner-suppressing API.

## Conventions

* We always adhere to Clean Code and SOLID principles. Keep in mind that this avoids unnecessary comments and rather
  uses speaking variable and function names.
* Use Kotlin sugar to make the code more readable.
* When fixing bugs add regression tests if reasonably possible.
* _meta: JsonElement?` is threaded through every ACP model type exactly as the SDK does.
* Secrets come from env vars only (`OPENROUTER_API_KEY`, ...); never commit or print them.
  * Exception (deliberate): the docker launcher never forwards `OPENROUTER_API_KEY`
    into the container env — it stages a `0600` copy (or mounts the user's
    `OPENROUTER_API_KEY_FILE`) read-only at `/tmp/openrouter-api-key`, and the agent env
    carries only the path. Rationale: anything in the agent's env is inherited by every
    bash-tool child and shows up in `set -x` traces / `env` dumps / `/proc/*/environ`
    (this actually leaked a real key into a session once); docker `--env K=V` also puts
    the value into the docker CLI argv visible to host-side `ps`. The agent-side
    resolution lives in `Config.resolveApiKey`.
  *`build/` is generated output - never edit or commit it.
* **Never commit without the user requesting it**: the agent must not run
  `git commit` (or otherwise write to git history, e.g. `git push`, `git tag`)
  unless the user explicitly asks. The `git` run config is for read-only
  inspection (`status`, `diff`, `log`) unless the user requested a mutating
  git command.

## Building / running

Use the `run` tool's configurations for the standard dev loop:

- `compile` — compile main sources (fastest loop)
- `test` — full suite (unit + black-box e2e; drives the installDist launcher)
- `test_class` — single test class/method via `{args}` (re-links installDist)
- `build` — assemble + all tests
- `install_dist` — relink the e2e launcher
- `dependency_updates` — outdated deps (stable-only)

The launchers take no args; env config only (one of `OPENROUTER_API_KEY` /
`OPENROUTER_API_KEY_FILE` required):

- `./ddr-acp-agent` — direct (no docker); always runs `installDist` first, then execs the launcher
- `./ddr-acp-agent-docker` — sandboxed Docker run (see Features / README)

**Shutdown**: `runAgent` loops until `transport.state.value == Transport.State.CLOSED`
(small delay) then `protocol.close()`.

### Configuration (OpenRouter + MCP)

Config comes from environment variables:

- `OPENROUTER_API_KEY` (required unless `OPENROUTER_API_KEY_FILE` is set)
- `OPENROUTER_API_KEY_FILE` (optional; file holding the key, read + trimmed at
  startup; the docker launcher always injects it pointing at the mounted key file
  and never forwards the key itself)
- `OPENROUTER_MODEL` (default `openrouter/auto`)
- `OPENROUTER_BASE_URL` (default `https://openrouter.ai/api/v1`)
- `OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED` (default enabled; `0` disables
  the automatic provider routing, see Features)
- `FS_PROXY_ENABLED` (default enabled; `0` uses the local store even when the
  client advertises fs capabilities, see Features)
- `MCP_TRUST_ANNOTATIONS` (default enabled; `0` ignores MCP tool behavior
  annotations, keeping the pessimistic always-prompt permission default, see
  Permissions)
- `ACP_BASH_TIMEOUT_SECONDS` (default 600, clamped to >= 1; the bash tool terminates commands
  after this many seconds, killing the whole process tree, see Features)
- `ACP_MAX_TURN_REQUESTS` (default 100, clamped to >= 1; the per-prompt tool iteration
  budget, see Features; when the cap is hit, a final text-only synthesis pass is streamed
  before the turn ends with `MAX_TURN_REQUESTS`)
- `ACP_WEB_FETCH_ALLOW_PRIVATE` (default blocked; `1` lets `web_fetch` reach
  private/loopback/link-local hosts - tests against a local mock server need this)
- `ACP_EXTRA_MOUNTS` (default empty; comma-separated **absolute** paths that are
  read-trusted: `read_file`/`list_dir`/`glob`/`grep` under them run without a permission
  prompt even though they lie outside the cwd; writes/moves/deletes still prompt. The
  docker launcher derives it from the effective `ACP_DOCKER_EXTRA_MOUNTS`; see Features /
  Permissions)

## Features

- **Lifecycle & persistence**: `initialize` -> `session/new` (random `sess_` + 16 hex digits) ->
  `session/prompt` -> `session/delete` (closes MCP connections + `LlmClient`, removes the
  record); `session/cancel` is coroutine cancellation. Sessions persist to
  `$XDG_STATE_HOME/ddr-acp-agent/sessions/<sessionId>.json` (fallback `~/.local/state`),
  atomically (temp + move) on every completed turn and mode/config change. The record
  (`agent/SessionRecord.kt`) holds history (Koog `OpenAIMessage` wire types via the shared
  `llmWireJson`), mode, title (first user message, 72 code points), model, reasoning and plan.
  Persist and delete share a `persistMutex` so a delete never resurrects the file; failures log
  to stderr and never fail the turn. Client-supplied ids are format-checked (`isValidSessionId`)
  so traversal/separators never reach the filesystem. Session creation (and restore) closes any
  already-opened MCP connections and the `LlmClient` when the model feed fetch fails.
- **Restore**: `initialize` advertises `loadSession` + `sessionCapabilities.list/delete/resume`.
  `session/load` reconnects MCP servers and replays history (user/agent chunks, pending
  `tool_call` + completed `tool_call_update`, plan) from `postInitialize()` - after the load
  response (SDK hook limitation); `session/resume` restores without replay. `session/list`
  filters by cwd, sorts by recency, skips corrupt records. Cwd mismatch, unknown/invalid ids and
  double-loads are invalid-params; corrupt records are internal errors.
- **Agent loop**: up to `ACP_MAX_TURN_REQUESTS` tool-calling LLM iterations per prompt (default 100,
  `ACP_MAX_TURN_REQUESTS`, clamped to >= 1); text streamed as `AgentMessageChunk`,
  tool-call deltas merged, results appended to history; a turn stops on `END_TURN` (no tool call) or,
  when the iteration budget is exhausted while the model kept calling tools, streams one final
  text-only synthesis pass (tools omitted from the request) that summarizes what was done and what
  remains, then ends with `MAX_TURN_REQUESTS`. Tool calls run sequentially. The session is a thin
  SDK facade over focused components: `SessionState` (mutable state + record lifecycle),
  `SystemPromptBuilder`, `SessionConfigOptions` (config option strategies), `ToolCallExecutor`
  (one tool-call lifecycle: mode gate -> permission -> execution -> updates) and `PromptRunner`
  (the loop + wind-down pass, over the `ChatCompleter` seam so the runner is unit-testable).
  New session code should extend a component, not the facade.
- **Modes (plan/build/bash)**: read-only `plan` default; `build` adds write tools; `bash` adds
  the permission-gated bash tool. `ToolRegistry.availableForMode/disabledInMode` filter tools
  and produce the "disabled in current mode" error. Mode enforcement happens in the agent loop *before* permission and
  execution: a tool call for a registered tool that is disabled in the
  current mode fails with the "disabled in current mode" update and never reaches the permission
  flow (so a model carrying a tool call over from an earlier mode switch cannot execute it; see
  `E2eModeRestrictionTest` and `E2eDeferredModeSwitchTest`). The system prompt is **mode-invariant**: it holds
  a static "Modes" table (what plan/build/bash mean) and no current-mode
  statement; the current mode and the tools available in it are stated in the
  conversation history instead — modal status messages (`OpenAIMessage.System`,
  LLM-internal, never rendered by the client`: a fresh session seeds one at
  session start (`SessionState.init`) and every applied mode change appends one,
  each stating the mode's semantics ("Mode: ... Read-only / Read-write / ...")
  plus the tools available in it (`ToolRegistry.availableForMode`)). **Mode
  switches are deferred**: a `mode` request while a prompt turn is running only
  records a pending mode on `SessionState` (`requestMode`, latest wins) and is
  applied as a single flush when the turn hands control back
  (`flushPendingMode` in the prompt flow's try, before `setPromptActive(false)`),
  with one `current_mode_update` at that point; the `finally` drops whatever is
  still pending and clears `promptActive` (so a cancelled turn never leaks its
  requested mode into the next turn), and `notifyModeState` is suppressed only
  while a mode is pending (`hasPendingMode`) so mid-turn model/reasoning
  switches still notify immediately. Idle switches still
  apply immediately. The prompt is still rebuilt per
  turn so mid-session `create_run_config` / `AGENTS.md` edits apply (it carries
  `cwd`, today's date, the available run configurations (name + command +
  description, re-read from local disk per iteration) and `Agent build: <sha>[-dirty]`).
  A `mode` config option (`session/set_config_option` + legacy `set_mode`) echoes
  the *governing* mode until the flush; unknown -> invalid-params.
- **Run configurations**: the `run` tool (every mode, `tools/RunTool.kt`) executes a
  configuration from `<cwd>/.ai/run.json` (`{"name": {"command": "...", "description": "..."}}`,
  read from local disk every access, fail-open like AGENTS.md). The tool's description is a static,
  location-agnostic string; the available configs (name + command + description) are surfaced in
  the system prompt instead. Commands are shell strings run
  via `ProcessRunner`; the model's `args` are substituted for **every** `{args}` occurrence (was first-only, which
  leaked a literal `{args}` into the shell for multi-placeholder
  configs),
  configs without the placeholder reject arguments. `mutating = false` so `run` never asks for
  permission in any mode (incl. plan); the trust model is that the config file is
  project-controlled (same trust tier as AGENTS.md). The `title` override (`run(config: ...)`,
  see the tool-call title note under Permissions) still renders in tool-call progress.
  Configs are managed by explicit dedicated tools (`tools/RunConfigTools.kt`, also read from
  local disk): `list_run_configs` (read-only, every mode) and `create_run_config` /
  `update_run_config` / `delete_run_config` (mutating, build/bash only, so plan stays
  read-only). `update` is field-level - a blank `command` is always rejected (never cleared),
  an omitted field stays unchanged, an empty-string `description` clears it; create rejects
  existing names and blank commands; delete/update reject unknown names. Writes are atomic
  (temp + move) and refuse to touch a corrupt/unparseable file; unknown entry fields
  round-trip untouched. The repo ships a default `.ai/run.json` with the standard dev
  loop (already available via `run`): `compile` (`compileKotlin`), `build` (full `build`),
  `test` (full `test` suite), `test_class` (single class/method via `{args}`),
  `install_dist` (relink the e2e launcher), `dependency_updates` (stable-only),
  `lint_scripts` (`bash -n` + `shellcheck` on the launchers/build/shell-test scripts),
  `test_scripts` (`tests/bash/run-all`, the shell test suite pinning the docker launcher
  composition - also wired into Gradle `check` as the `testScripts` Exec task),
  `show_failures` (failure messages from the latest JUnit XML reports, backed by
  `.ai/scripts/show-test-failures.sh`) and `sdk_sources` (extract a `*-sources.jar` from
  the Gradle cache for inspection via `.ai/scripts/sdk-sources.sh`, for the SDK contract
  checks). The gradle configs are
  wrapped in `timeout` (60s for the fast loop, 120s for the full `build`/`test` suites) so
  a hung daemon surfaces as a timeout instead of stalling the agent, plus a generic `git`
  config (`git {args}`, arbitrary arguments, read-only inspection only) and a `gradleStop`
  config (stop daemons and kill lingering processes holding cache locks). A
  `test_fsproxy`
  run config was removed because it is redundant: the e2e harness itself strips a
  leaked `FS_PROXY_ENABLED=0` from the spawned agent's environment (unless a
  scenario explicitly sets it), so the full `test` suite already covers the
  fs-proxy scenarios regardless of the host environment (`E2eAgentTest`).
- **Build hash**: `generateGitProperties` writes `git.properties` (`git.commit=<sha>[-dirty]`,
  `unknown` outside git) into resources; `BuildInfo.kt` reads it. Docker injects it via the
  `GIT_SHA` build-arg (no `.git` in the build context).
- **Model + reasoning options**: `session/new`/`load`/`resume` fetch the OpenRouter model feed
  (`llm/LlmModels.kt`: tool-capable text-output models, sorted; failure fails session creation).
  Per-session `model` (default `OPENROUTER_MODEL`) and `reasoning` effort
  (`category: thought_level`; empty `supported_efforts` -> gateway levels max..minimal;
  mandatory models drop `none`; `none` omits the request field). Model switches reset reasoning;
  changes emit updates and persist. The chat request carries `reasoning: {effort}` (own wire
  type in `LlmClient`, not Koog).
- **Plan updates**: `update_plan` (`tools/PlanTool.kt`, kind `think`, every mode) emits ACP
  `PlanUpdate` and stores entries for persistence/replay; decoded into the SDK's typed
  `PlanEntry` (strict enums - deliberate deviation from the Go raw-string passthrough).
- **Usage indicator**: after each model call a `usage_update` (`used` = prompt tokens,
  `size` = model `context_length`); skipped when either is missing. During an active prompt the
  SDK routes session updates into the prompt event flow, not the `notify` callback.
- **File-access exclusions**: the file tools enforce a shared exclusion policy
  (`tools/FileAccessExclusions.kt`, threaded as `ToolContext.fileExclusions`): files whose
  cwd-relative path matches an exclusion glob rule are refused to direct-target tools
  (`read_file`/`edit_file`/`write_file`/`delete_file` hard-error before any I/O - no client
  fs proxy round trip, no permission prompt, uniform incl. new-file writes) and hidden from
  listings/searches (`list_dir` filters entries before the 500-cap, `glob`/`grep` skip them
  in the walk callback; `grep` folds them into the existing skip suffix). The default rule
  set is the fixed `.env*.local` secret-file exclusion (bare name, matches at any depth;
  gitignore-style rooted rules like `secrets/**` match the cwd-relative path). The guard
  also checks the symlink-resolved target name, so an alias link to an excluded file cannot
  smuggle the read (unresolvable paths only skip that extra check - a block, not a gate).
  Refusals name the matched rule (`'<path>' is excluded from tool access (matches exclusion
  rule '<glob>')`) and the system prompt carries a static "Excluded files" section rendered
  from the policy's globs. Rule semantics: globs via the shared `globToRegex`, bare names
  match the basename at any depth. The designed future source is a gitignore-style
  `.aiignore` in the project root feeding `FileAccessExclusions.of()` - matching, error
  text, hiding and the prompt section already consume a rule list, so plumbing it changes
  no tool code. Not excluded on purpose: `bash`/`run` commands (not inspected) and
  `move_file`/`move_directory`/`delete_directory` (no content flow into the model context).
- **Tools**: `read/write/edit/move_file/move_directory/delete_file/delete_directory/list/glob/grep`
  (kotlinx-io) + `bash` (killed after
  `ACP_BASH_TIMEOUT_SECONDS`, whole process tree) + `web_fetch` (HTTP(S) GET via a
  per-call ktor CIO client, `requestTimeout = 0` + 60s socket idle; manual redirect
  loop (max 5) with per-hop SSRF re-validation - loopback/link-local/site-local/
  any-local IPs and `localhost`/`*.local`/`.internal` by name refused unless
  `ACP_WEB_FETCH_ALLOW_PRIVATE=1`, residual validate-then-fetch TOCTOU documented;
  only http/https; gzip/deflate via `ktor-client-encoding` - the encoders must be
  registered in the `install(ContentEncoding)` config block, the bare plugin rejects
  every encoded response; 20 MB body cap enforced by Content-Length pre-check and a
  progressive read; declared binary content types refused before download, lying
  types caught by a NUL sniff, both failing loudly with a "download via bash (curl)"
  hint - the tool never writes to disk; charset from Content-Type, UTF-8 fallback;
  non-HTML passes through, HTML is converted to lines by `WebContentConverter`
  (jsoup 1.23.2: block tags flush lines, `pre` verbatim, `li` bulleted, script/style
  stripped) so read_file-style `startLine`/`maxLines` paging works; numbered `│`
  lines, 2000-char line cap, continue footer; `ToolKind.FETCH`, non-mutating, no
  filesystem targets -> prompt-free in every mode; kind is the SDK enum value added
  for fetch tools) + `update_plan` + `get_current_mode`
  (`tools/GetCurrentModeTool.kt`; returns the turn-captured mode status text — mode,
  semantics and the tools available in it, the same text the modal status messages
  carry — threaded through `ToolContext.modeStatusText` so the model can verify the
  governing mode instead of inferring it; non-mutating, no parameters, every mode,
  prompt-free), registered in `Main.kt`,
  copied per session; MCP tools are bridged per session (`mcp/McpBridge.kt`) but a name
  collision with a local tool is ignored with a warning - locals can never be shadowed. All
  path-scoped tools resolve relative paths against the session cwd before I/O (the file touched
  is the one the permission check approved); `list_dir`/`glob`/`grep` skip symlinks (kotlinx-io
  follows them by default, which could smuggle reads outside the project). `read/write/edit`
  use the client fs proxy (`tools/FileStore.kt`, unsaved editor state + reviewable diffs) when
  the client advertises read+write fs capabilities and `FS_PROXY_ENABLED` is not `0`, else a
  local store; `read_file` renders all reads as fixed-width 1-indexed `number│content`
  lines (the content - including its leading indentation - is verbatim after the `│`, so
  the model can read indentation directly off the line rather than inferring it from a
  whitespace-only prefix), byte-identical except for the stripped trailing newline
  terminator, plus a
  `(Showing lines X-Y of N. Use line=Z and limit to continue.)` footer when the window does
  not cover the whole file, so a truncated read is
  unambiguous and the model can page forward (line numbers are display-only - `edit_file`
  matches raw content, so the model must strip the `number│` prefix; the client fs proxy returns no
  total, so the footer total comes from the local store, not the proxy; a proxy window of
  exactly `limit` lines ending with a newline is complete - the terminator is not a phantom
  line). Tool arguments are decoded strictly (`JsonObject.stringArg`/`longArg` in
  `tools/Tool.kt`): an explicit JSON `null` is rejected with a dedicated
  `'x' must not be null` error (previously `null` was silently coerced to the string
  "null" - e.g. written into files or run as a shell command), an absent key stays
  `Missing 'x'`. Tool-call diffs describe the **whole file** (`edit_file` was fragment-only
  before, which clients like Zed misrender) and are **skipped when the client fs proxy is
  active** (the client renders the change itself; also removes the stale pre-read
  round-trip). listing/search always use the local disk (ACP
  has no client-side search), and so
  do the move/delete tools (`tools/MoveFileTool.kt`/`MoveDirectoryTool.kt`/`DeleteFileTool.kt`/
  `DeleteDirectoryTool.kt` - ACP has no fs move/delete): `move_file`/
  `move_directory` rename (java.nio `Files.move`, no overwrite - an existing destination is
  refused - missing destination parents are created; kotlinx-io's `atomicMove` is a JVM stub,
  so java.nio is used like the run-config writes), `delete_file` deletes single files (refuses
  directories and symlinks; carries the removed content as a `Diff`), `delete_directory`
  deletes recursively but is refused when the tree contains any symlink (kotlinx-io follows
  links, so a link could smuggle the recursive delete outside the approved project); the
  symlink scan and the recursive delete are depth-capped (64) like the search walker.
  The search walker (`walk` in `GlobTool.kt`) always skips `.git` directories (packed
  object files are binary noise for content searches) and symlinks.
- **Output caps**: tool results are bounded so a misbehaving command or huge file cannot
  explode the context. `bash`/`run` keep the last 30k chars of stdout and stderr each,
  prepending `...(truncated: N chars omitted from the beginning)...` (Locale.ROOT; bounded
  memory while reading, UTF-8 chunk-safe) - the output is captured **progressively** into a
  synchronized tail buffer (`ProcessRunner.StreamTail`), so when a backgrounded child holds
  the pipe fds open and the drain grace expires, the tree is force-killed, re-drained once,
  and the output captured so far is snapshotted, never discarded (before this, such a command
  surfaced a fake `(no output, exit N)`); a reader I/O error is recorded into the captured
  output. `read_file` requires `limit` (1..2000 lines; anything else is
  rejected before I/O), refuses files over **20 MB** (hardcoded, with a "use bash" hint),
  truncates lines over **2000 chars** with `... [truncated]`, and errors with
  `line N is past the end of the file (M lines)` when `line` is beyond EOF (the local store
  streams via the byte-level `StreamingLineReader` - a chunked `InputStreamReader` was
  observed to spin forever on zero-char reads under JDK 25); `list_dir`/`glob` list at most
  500 entries with a `...(N more entries omitted)` suffix; `grep` caps matches at 500,
  truncates each matched line at 500 chars (`...` suffix), skips binary files (NUL sniff),
  files over 1 MB and excluded files (see File-access exclusions), and reports skips as
  `...(N binary, oversized or excluded files skipped)`.
  `FileStore.readRaw` (exact bytes, size-capped, no per-line truncation) is the store
  operation for `edit_file` matching and the diff pre-reads - display reads (`readFile`) may
  be truncated, raw reads must not be. MCP tool results are intentionally uncapped.
- **Permissions (path-aware)**: path-scoped tools inside the session cwd run without asking;
  anything outside - reads and writes alike - and any mutating non-path tool (`bash`) ask via
  `session/request_permission`. Exception: `ACP_EXTRA_MOUNTS` names absolute **read-trusted**
  paths (the docker launcher derives it from the effective `ACP_DOCKER_EXTRA_MOUNTS`); the
  non-mutating path tools (`read_file`/`list_dir`/`glob`/`grep`) run prompt-free when all
  targets resolve inside one of them (`isWithinAnyRoot` in `tools/Containment.kt`, resolved
  against the session cwd so a symlink escape still gates); writes/moves/deletes under them
  always prompt. The paths are also surfaced as a static "Trusted read paths" system-prompt
  section (`SystemPromptBuilder`) so the model knows reads there are prompt-free. MCP tools
  always prompt **unless** the server annotates the
  tool `readOnlyHint: true` and annotations are trusted (`MCP_TRUST_ANNOTATIONS`, default
  enabled; annotations are untrusted hints per the MCP spec, so absent/unset hints keep the
  pessimistic always-prompt default). The trusted `title` annotation replaces the bare tool
  name in permission prompts and tool-call progress (MCP names are often machine-prefixed);
  the display kind derives from `readOnlyHint`/`destructiveHint` (read-only `other`,
  non-destructive `edit`, potentially destructive `delete`) and is cosmetic only.
  A tool's targets come from `AgentTool.targetPaths(arguments)` (`tools/Tool.kt`, defaults to
  the single `targetPath`; `move_file`/`move_directory` override it with source + destination)
  and every target must lie inside the cwd for a prompt-free call - a move with an
  out-of-project destination asks even when the source is inside.
  In-project writes are further gated by mode (plan is read-only). The containment check
  (`tools/Containment.kt`) is symlink-safe and resolves relative paths against the session cwd.
  `allow_always`/`reject_always` persist per session (keyed by tool name); `allow_once`/
  `reject_once` apply once. **Tool-call titles**: the JetBrains ACP client renders only the `title` of a tool call in
  permission prompts and progress - it ignores the `rawInput` field that carries the actual
  arguments, so a bare `run`/`bash` title leaves the user confirming blind. Tools with
  **Observed (JetBrains, 2026-09)**: the permission dialog in practice showed the bare
  argument value (`echo permission-test`) instead of our `bash(command: ...)` title - the
  "only title is rendered" claim did not hold for the permission surface. Keep titles
  argument-bearing anyway (other clients render them) and make commands self-describing;
  re-verify against a concrete client rather than assuming. Working hypothesis
  (unverified, no matching JetBrains bug report found 2026-09): the dialog
  special-cases a `command` value in `rawInput` and renders it as a
  syntax-highlighted runnable block, falling back to the title otherwise - this
  fits `create_run_config`/`bash` showing their `command` argument while `run`
  (`config`/`args` keys) and `delete_run_config` (`name` only) showed their
  titles, and matches the JetBrains rendering behavior reported in
  google-gemini/gemini-cli#23018 (title rendered as command block) and the
  "bug in how the IntelliJ ACP client renders tool call parameters" confirmation
  on the Cursor forum. All of this is hypothesis until verified against a
  concrete client. Tools with
  meaningful arguments therefore override `AgentTool.title(arguments)` (hook in
  `tools/Tool.kt`, default `null` = bare tool name). The `run` tool and the
  run-config write tools lead with the config name
  (`run(echo)`, `run(echo: <args>)`, `create_run_config(echo: <command>)`,
  `delete_run_config(echo)`) so client-side title elision cannot hide which
  configuration is executed/created - `formatRunToolTitle` /
  `formatRunConfigToolTitle` in `tools/Tool.kt` read named keys, so titles are
  order-independent regardless of the JSON key order the model chose. The
  remaining tools use `formatToolTitle(name, args)` - a `name(key: value, ...)`
  summary, gemini-cli style - and every formatter caps each value part at 100
  chars (ellipsized; was 50 for the whole argument part, which could push the
  identifying name out of the title);
    `rawInput` is still sent unchanged for clients that do render it (Zed). Currently
    implemented for `bash`, `run`, the run-config write tools and the move/delete tools (their prompts are
    safety-critical: a bare title would leave the user confirming a
    deletion blind); titles flatten embedded newlines so permission prompts stay
    single-line; the remaining path tools only prompt for out-of-project access and are
    untouched. **Tool-call results**: completed/failed
    `tool_call_update`s carry the result text as `content` blocks (and the error text on
    permission denial), so clients that ignore `rawOutput` still render the outcome;
    edit-kind tools additionally emit `ToolCallContent.Diff` (spec v1 `diff` blocks) so
    file changes are visible without the client fs proxy - every diff describes the **whole file**: `edit_file` emits
    the full old/new content (was fragment-only, which
    Zed-style clients misrender), `write_file` best-effort pre-reads the old
    content (`oldText = null` for new files; skipped on read failure or when either side
    exceeds 100k chars), `delete_file` carries the removed content as
    `newText = ""`. All diff blocks are **skipped when the client fs proxy is active**
    (the client renders the change itself; avoids a stale duplicate and a pre-read
    round-trip). Path-scoped tool calls carry `locations`
    (`tool_call`, `request_permission` and load replay) for the client's follow-along
    surface. `rawOutput` keeps the plain result for wire compat. **Revert path**: when JetBrains renders
    `rawInput` (or ACP v2 permission `subject`), drop the one-line `title` overrides and the
    call sites (`tool_call` notification, `request_permission`, completed/denied updates,
    load replay in `AgentSessionImpl.kt`) fall back to `tool.name` without further changes.
- **MCP consumption**: servers come exclusively from the client's `session/new` `mcpServers`;
  all three transports work on JVM (see Recipes); `initialize` advertises
  `mcpCapabilities.http/sse`. The streamable-HTTP client (`McpConnector.connectHttp`)
  installs the ktor SSE plugin so the transport's optional GET SSE probe degrades cleanly on
  a 405 ("stream disabled") instead of throwing - JSON-only servers (no GET stream) work.
  MCP tool behavior annotations (`Tool.annotations`) drive the permission decision when
  trusted (`MCP_TRUST_ANNOTATIONS`, default enabled; see Permissions) - pinned black-box by
  `E2eMcpToolPermissionTest` with the in-process `MockMcpServer` (streamable-HTTP JSON-only
  mock: JSON POST responses, 202 for `notifications/initialized`, 405 for GET/DELETE;
  tools carry the mandatory `inputSchema`).
- **LLM streaming**: OpenRouter via its OpenAI-compatible streaming API (hand-rolled line scan,
  see Boundaries); text deltas relayed immediately, tool-call deltas merged, `delta.reasoning`
  relayed as `agent_thought_chunk` (not persisted); empty `delta.content` (sent by
  reasoning-capable providers alongside `delta.reasoning`)is filtered so reasoning deltas
  emit no blank `agent_message_chunk`s. HTTP error statuses, `{"error": ...}`
  stream events and a stream ending without `[DONE]` or a `finish_reason` raise `LlmException`
  so a failed or truncated turn fails loudly instead of executing partial tool calls or
  emitting an empty END_TURN. A `length` (or other non-`stop`/`tool_calls`/
  `content_filter`) finish reason, or an iteration with no text and no tool calls, triggers
  exactly one continuation pass:the partial text (if any) is kept, ALL tool calls (including complete ones) are dropped
  and never executed - the model re-issues them
  after the continuation -, a synthetic user "continue" prompt is appended, and the retry
  runs even when the tool-iteration budget is exhausted. A second truncation/empty
  completion ends the turn with an honest "response interrupted" note (+ `end_turn`,
  no exception); `content_filter` ends immediately with a "blocked by content filtering"
  note (never retried). Both cases log to stderr (`warn` on first, `error` when still
  broken) so the problem stays visible. Replay renders the synthetic "continue" user
  message as a normal bubble (accepted).
  Every `agent_message_chunk`/`agent_thought_chunk` of one LLM iteration carries the same
  UUIDv7 `messageId` (time-ordered, `com.github.f4b6a3:uuid-creator`; fresh per iteration) so
  clients group the iteration's reasoning and reply into a single message; pinned e2e at
  the decoded-object and raw-wire level.
  **Pitfall**: the `messageId` RFD's "unique per message" is still violated by sharing one
  id across the distinct `agent_message` and `agent_thought` streams of the same iteration -
  deliberate v1 choice so reasoning folds into the message block it belongs to. v2 makes
  `messageId` mandatory and keys whole `agent_message`/`agent_thought` messages by it, so
  this **must change** on v2 (distinct ids per message). Re-verify against v2 when the agent
  runtime is upgraded.
  Requests carry app attribution headers (`HTTP-Referer`
  + `X-OpenRouter-Title`).
- **Auto provider routing**: by default (`OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED=0`
  disables) every chat request carries `provider: {sort: "throughput", max_price.completion =
  median endpoint completion price}` (USD per million tokens,
  `providerrouting/ProviderRouting.kt`, lazy per-model cache); fail-open: fetch/parse failure
  or empty feed omits the `provider` field; disabled routing never fetches endpoints.
- **Prompt capabilities**: advertises `image` + `embeddedContext` (no `audio`). Image blocks
  become base64 data URIs (mime default `image/png`) only when the model's
  `architecture.input_modalities` lists image, else a text placeholder; text `resource` blocks
  are inlined as `Resource <uri>:\n<text>`, blob/audio degrade to placeholders,
  `resource_link` renders as a markdown link (`contentBlocksToLlmContentTopLevel` in
  `agent/AgentSessionImpl.kt`).
- **Project instructions**: `<cwd>/AGENTS.md` is read from disk every turn and injected as
  `## Project Instructions (from AGENTS.md)` (re-read per turn, so mid-session edits apply);
  missing file ignored, read errors logged and never fail the turn (`agent/AgentsMd.kt`).
- **`$/cancel_request`**: handled natively by the SDK in both directions; a cancelled
  `session/request_permission` dismisses the client's prompt, and `shouldAllow` rethrows
  `CancellationException` instead of swallowing it into a bogus "Permission denied". A
  cancelled turn also terminates a running bash command (interruptible wait +
  process-tree kill), so `session/cancel` does not leave orphans behind.
  Cancellation must **propagate out of tool execution** (`runShellCommand` in
  `tools/BashTool.kt` rethrows it before the generic catch, like `WebFetchTool`; the
  executor rethrows at `ToolCallExecutor.kt`): a swallowing tool turns `session/cancel`
  into a bogus failed `ToolResult`. Same contract for the fs-proxy file tools
  (`ReadFileTool`/`EditFileTool`/`WriteFileTool` - with the client fs proxy their
  `FileStore` calls are suspending RPCs; #28, fixed 2026-09-24, incl. the inner
  `writeResultDiff` pre-read catch) and for the process tools' duplicate run/format
  helper. Testing this swallow needs an outcome-capture
  detector, not `Deferred.isCancelled` — a `cancelAndJoin`ed deferred is
  `isCancelled` even when the body swallowed and returned a value
  (`BashToolTest`/`RunToolTest` "cancelling the tool during a running command"
  tests; verified to fail with the bug re-introduced). Today the SDK's
  `SafeCollector.emit` (`currentContext.ensureActive()`) masks a swallowed
  cancellation before the bogus `ToolCallUpdate(FAILED)` is emitted, so the
  end-to-end symptom is latent, not observed.
- **Docker sandbox**: CI-built (`build-image.yml`) image on
  `ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest`; multi-stage Dockerfile (temurin-25
  builder with BuildKit cache mount, installDist perms normalized -> toolchain base `dev`).
  The `ddr-acp-agent-docker` launcher runs it hardened: `--cap-drop=ALL` +
  `no-new-privileges`, read-only rootfs (`ACP_DOCKER_RW_ROOTFS=1` relaxes), tmpfs `/tmp` and
  home (`ACP_DOCKER_HOME_VOLUME` -> named volume) plus uid-mapped tmpfs mounts for
  `~/.local`, `~/.local/share` and `~/.local/state`: the image has no `~/.local` and a
  home tmpfs/named volume would shadow it anyway, so runc would create the deep
  bind-mount mountpoints (session state at `~/.local/state/ddr-acp-agent`, pnpm store)
  root-owned inside the container and the agent uid could not write anything else
  under `~/.local`. The state/store binds land on top of these tmpfs mounts (moby
  composes all mounts shallowest-first into the OCI spec and runc mounts in that
  order, so CLI argv order is irrelevant - the launcher still emits them in argv
  order), pinned by the shell suite; non-root user matching host UID/GID,
  host tool caches shared in (`ACP_DOCKER_MOUNT_CACHES=0` disables) when the tool's env var
  (absolute; created if missing) points at a custom dir or the host default dir already
  exists (never created) - mounted at the tool's in-container default with the env var
  pinned to the mount (`KONAN_DATA_DIR` -> `~/.konan`, `UV_CACHE_DIR` -> `~/.cache/uv`,
  ...), host Android SDK shared in (`ANDROID_HOME` first, else `ANDROID_SDK_ROOT`,
  else an existing `$HOME/Android/Sdk` - never created; mounted at the in-container
  default `$HOME/Android/Sdk` with `ANDROID_HOME`/`ANDROID_SDK_ROOT` pinned to it;
  a set-but-unusable var - relative, `/`, missing dir - fails loudly before any
  `docker run`; when the SDK lies inside the project dir the mount is skipped and
  the pins keep the host path, since the project is mounted at the identical path),
  host session state always shared rw (`ACP_DOCKER_STATE_DIR` overrides the
  host-side dir - absolute paths only, falls back to `$XDG_STATE_HOME/ddr-acp-agent`),
  `OPENROUTER_*`/`FS_PROXY_ENABLED`/`MCP_TRUST_ANNOTATIONS`/
  `ACP_BASH_TIMEOUT_SECONDS`/`ACP_MAX_TURN_REQUESTS`/`ACP_WEB_FETCH_ALLOW_PRIVATE` forwarded
  (the API key excepted: it is staged as a `0600` file or taken from
  `OPENROUTER_API_KEY_FILE` and mounted read-only at `/tmp/openrouter-api-key`, with only
  the path in the container env; the staged copy and the `.env.local` mask are removed by
  an EXIT trap, which is why the launcher does not `exec` docker),
  host `.env.local` masked, git identity forwarded. Optional extra host paths
  (`ACP_DOCKER_EXTRA_MOUNTS=/srv/data,/mnt/scratch:rw`) are bind-mounted at the
  identical in-container path (src == dst like the CWD; mode suffix `:ro` default /
  `:rw`; dirs or single files; absolute, must exist, relative paths / `/` are
  rejected loudly); the launcher injects the *effective* extras as
  `ACP_EXTRA_MOUNTS` so the agent treats them as read-trusted (skipped entries -
  inside the project dir/home/tmpfs - are never trusted). Extras are emitted before every built-in mount and sorted
  shallowest-first, so built-ins and later, deeper mounts shadow shallower ones
  (mounting a CWD parent ro works; the project dir itself stays rw); entries inside
  the project dir or container home are skipped with a warning. Extras:
  `ACP_DOCKER_NETWORK`, `ACP_DOCKER_CAP_ADD`, `ACP_DOCKER_EXTRA_ARGS`, `DOCKER_BIN`;
  local builds via `./build-docker`. Launcher output is stderr-only - ACP travels over the container
  stdin/stdout.

## Layout

```
src/main/kotlin/net/dontdrinkandroot/acpagent/
    Main.kt                          # `main` (logging setup) + `runAgent`: registry, session
                                     # factory (AgentSessionFactory: create/restore), store
                                     # wiring, transport, isoDateToday
    BuildInfo.kt                     # build commit hash from git.properties (classpath)
    config/Config.kt                 # env config (OPENROUTER_*, FS_PROXY_ENABLED,
                                     # MCP_TRUST_ANNOTATIONS)
    config/PlatformEnv.kt            # platformEnv(): System.getenv() env source for config
    llm/LlmClient.kt                 # LLM transport only (HTTP/SSE/JSON); implements ChatCompleter
    llm/ChatCompleter.kt             # chat-completion seam (LlmClient implements it) so the prompt
                                     # runner is testable with a fake stream
    llm/LlmWire.kt                   # the single shared `llmWireJson` (snake-case etc.) for all
                                     # wire-shaped LLM data (chat traffic + persisted history)
    llm/LlmModels.kt                 # `GET /models` wire types (models feed, reasoning capability,
                                     # provider endpoints feed)
    providerrouting/ProviderRouting.kt  # auto provider routing: throughput sort + median completion cap
    mcp/McpBridge.kt                 # MCP ServerConnection, McpTool (annotation-derived
                                     # mutating/kind/title), JsonObject->Any map
    mcp/McpConnector.kt              # stdio/HTTP/SSE connect helpers (JVM; HTTP installs SSE)
    agent/SessionRecord.kt           # durable per-session state (history, mode, title, updatedAt)
    agent/SessionStore.kt            # atomic save/load/list/delete + isValidSessionId path guard
    agent/SessionState.kt            # mutable session state (history, plan, title, mode/model/reasoning,
                                     # permanent permissions) + record lifecycle (buildRecord/persist/delete)
    agent/AgentSessionImpl.kt        # thin SDK facade: wires the components below, implements the
                                     # AgentSession interface (config options, replay, prompt plumbing)
    agent/SystemPrompt.kt            # SystemPromptBuilder: pure system-prompt assembly (cwd, date,
                                     # build hash, mode description, run configurations,
                                       # AGENTS.md instructions)
    agent/SessionConfigOptions.kt    # config surface (mode/model/reasoning): option listing,
                                     # validation/assignment, effective reasoning effort
    agent/ToolCallExecutor.kt        # one tool-call lifecycle: mode gate, unknown-tool, permission
                                     # (path-aware + permanent), execution, updates + history
    agent/PromptRunner.kt            # the agent loop: LLM iteration cap, streamed deltas relayed,
                                     # tool calls executed sequentially, wind-down synthesis pass
    agent/AgentsMd.kt                # AGENTS.md loader + instructions section for the system prompt
    agent/AgentSupportImpl.kt        # AgentSupport impl (initialize, create/load/resume/list/delete
                                     # sessions, AgentSessionFactory interface, randomSessionId)
    tools/Tool.kt                    # AgentTool, ToolContext (incl. fileExclusions), ToolResult
    tools/FileAccessExclusions.kt    # file-access exclusion policy: glob rules, matching, refusal
                                     # text; DEFAULT = .env*.local; future source: .aiignore
    tools/ToolRegistry.kt            # tool registry + mode filtering (availableForMode/disabledInMode)
    tools/ToolSchema.kt              # JSON-schema helpers for tool parameters (jsonSchema, Prop)
    tools/BashTool.kt                # bash tool (ProcessBuilder)
    tools/WebFetchTool.kt            # web_fetch tool (line-paged HTTP fetch, ToolKind.FETCH)
    tools/WebFetcher.kt              # fetch pipeline: ktor CIO client, redirects + SSRF guard,
                                     # gzip/deflate, 20 MB cap, NUL sniff, charset, binary refusal
    tools/WebContentConverter.kt     # jsoup HTML -> line-based text (block tags, pre, li), pass-through otherwise
    tools/Containment.kt             # isWithin / resolveAgainstSessionCwd (symlink-safe containment)
    tools/FileStore.kt               # FileStore interface, LocalFileStore, ClientFileStore (fs proxy)
    tools/PlanTool.kt                # UpdatePlanTool (emits ACP PlanUpdate, stores plan on session)
    tools/GetCurrentModeTool.kt      # get_current_mode tool (mode status text via ToolContext)
    tools/RunTool.kt                 # run tool (static description; configs surfaced in the system
                                     # prompt) + run-config storage (load/create/update/delete, atomic write)
    tools/RunConfigTools.kt          # list_run_configs + create/update/delete_run_config tools
    tools/ReadFileTool.kt            # read_file tool (via FileStore)
    tools/WriteFileTool.kt           # write_file tool (via FileStore)
    tools/EditFileTool.kt            # edit_file tool (via FileStore)
    tools/ListDirTool.kt             # list_dir tool (local disk)
    tools/GlobTool.kt                # glob tool + internal walk/globToRegex helpers (local disk)
    tools/GrepTool.kt                # grep tool (local disk)
    tools/MoveFileTool.kt            # move_file tool + shared movePath helper (local disk)
    tools/MoveDirectoryTool.kt       # move_directory tool (local disk)
    tools/DeleteFileTool.kt          # delete_file tool + removed-content diff (local disk)
    tools/DeleteDirectoryTool.kt     # delete_directory tool; symlink-safe recursive delete
                                     # (containsSymlink/deleteRecursively helpers)
src/test/kotlin/                              # unit tests + black-box e2e harness
    net/dontdrinkandroot/acpagent/e2e/
        E2eAgentTest.kt              # abstract base: process/stdio transport harness, temp-dir
                                     # lifecycle (withE2eAgent), connect helpers, prompt helpers
        MockOpenAiServer.kt          # mock OpenRouter server (SSE chunks, models + endpoints feed)
                                     # + MockToolCall / pathArgs
        MockMcpServer.kt             # in-process streamable-HTTP MCP mock (JSON POST responses,
                                     # 202 initialized, 405 GET/DELETE) with annotated tools
                                     # (readOnly+title / unannotated / destructive+title) + marker file
        ClientOperations.kt          # client session ops doubles: TestClientOperations
                                     # (records requests/notifications, allow_once) +
                                     # SuspendingPermissionOperations (stuck permission prompt)
        E2eWireConformanceTest.kt    # initialize/session/config-option/prompt/tool-call/permission
                                     # flow + UUIDv7 messageIds, usage_update, attribution headers
        E2eSessionPersistenceTest.kt # session record/list/load(replay)/resume/delete across
                                     # restarts + update_plan persistence/replay
        E2eCancelTest.kt             # $/cancel_request dismisses a stuck permission prompt +
                                     # cancel during a running bash command (no FAILED update)
        E2eModeRestrictionTest.kt    # registered tool disabled in the current mode is refused
                                     # before permission/execution (bash in build mode)
        E2ePromptCapabilitiesTest.kt # AGENTS.md injection + multimodal prompt conversion
        E2eFileStoreTest.kt          # client fs proxy (on/off) + out-of-project read permission
                                     # + trusted read paths (ACP_EXTRA_MOUNTS: read prompt-free, write
                                     # still prompts) + move/delete tools (in-project without prompt,
                                     # out-of-project move destination prompts)
        E2eExcludedFilesTest.kt      # file-access exclusions: read_file on .env.local fails
                                     # without reading/prompting, list_dir hides the entry
        E2eProviderRoutingTest.kt    # auto provider routing: median cap, fail-open, disabled
        E2eMaxTurnRequestsTest.kt    # agent loop iteration cap + wind-down synthesis pass
        E2eRunToolTest.kt            # run tool: prompt-free in every mode, on-disk side effect,
                                     # unknown config fails loudly
        E2eRunConfigCrudTest.kt      # create/update/delete_run_config: .ai/run.json persistence
                                     # and permission prompts
        E2eMcpToolPermissionTest.kt  # MCP tool permissions via annotations: readOnly prompt-free
                                     # (trusted), unannotated prompts + executes,
                                     # MCP_TRUST_ANNOTATIONS=0 prompts, destructive annotated
                                     # title, mcpCapabilities.http advertisement
README.md                               # user-facing readme (install, IDE setup, config, troubleshooting)
Dockerfile                              # multi-stage image: temurin-25 builder -> dev base
ddr-acp-agent                           # direct launcher (no docker): always runs installDist
                                        # first, then execs the binary; embedded Gradle stdout
                                        # is redirected to stderr
ddr-acp-agent-docker                    # docker launcher: sandboxed `docker run` for the agent
build-docker                            # local image build script (tags
                                        # ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest)
tests/bash/                             # shell test suite (launcher composition; run via
                                        # `test_scripts` / Gradle `testScripts`): harness.sh
                                        # (assert lib), common.sh (launcher invocation +
                                        # sandbox under build/), stubs/fake-docker (DOCKER_BIN
                                        # stub recording argv; pull/inspect exit switches),
                                        # test_extra_mounts/test_launcher_args/test_error_paths
                                        # + run-all
.github/workflows/build-image.yml        # CI: builds/pushes image to GHCR on push to main, prunes all but the 5 newest versions
.dockerignore                           # build context exclusions (.git, build/, .gradle/)
```

## Tool Usage

* **Run configurations**: the `run` tool's named configurations serve the standard
  build/test/lint loop (see Building / running). They are managed via
  `list_run_configs` / `create_run_config` / `update_run_config` /
  `delete_run_config`, and surface in the system prompt.
* **Web research**: use the `exa_web_search_exa` and `exa_web_fetch_exa` tools for research, validating information and
  looking things up when unsure — do not guess. Search first with `exa_web_search_exa`, then fetch the full page with
  `exa_web_fetch_exa` when highlights are insufficient. Verify API contracts, library versions, spec details and
  upstream behavior against primary sources before relying on them; cite the sources you checked in your summary.
* **GitHub issues**
  * Always set the **issue type** (`Bug` / `Feature` / `Task`).
  * Every **bug** additionally gets the **`Priority`** field (`Low`, `Medium`, `High`, `Urgent`)
  * Avoid redundant labels (like "bug" for a bug).
  * **Proactively create issues for newly discovered bugs** — file them, don't just mention them.
  * GitHub issues are the sole issue tracker.

## Testing

Drive the suites through the run configurations: `test` for the whole suite,
`test_class` (single class/method via `{args}`; re-links installDist) for one
target, `show_failures` for the failure messages of the last run.

The suite is unit tests plus a **black-box e2e harness** (feature-area scenario
classes in `net/dontdrinkandroot/acpagent/e2e` sharing the `E2eAgentTest` base).
The e2e scenarios run the linked `installDist` launcher as a separate OS process
with the **real ACP SDK client** and a local mock OpenRouter server, and pin the
agent's **wire contract**:

- **Wire flow** — initialize/session/config-option/prompt/tool-call/permission,
  model/reasoning switches, the chat request's `reasoning` effort, `usage_update`,
  UUIDv7 messageIds, attribution headers.
- **Capabilities** — `promptCapabilities` (image/embeddedContext) + multimodal
  conversion (image data URI, inlined resources), `mcpCapabilities` (http/sse),
  AGENTS.md injection, the `Agent build:` hash round-tripped from `git.properties`.
- **Security & permissions** — path-aware permissions + client fs proxy
  (in-project prompt-free, out-of-project read prompts, `FS_PROXY_ENABLED=0`
  local-store fallback), run tool prompt-free in every mode, run-config tools
  (build-mode permission prompt, plan-mode absence of write tools), move/delete
  tools (out-of-project move destination prompts), MCP tool annotations
  (`E2eMcpToolPermissionTest` over `MockMcpServer`), `$/cancel_request` dismissal
  of a stuck permission prompt, mode enforcement
  (`E2eModeRestrictionTest`: registered tool disabled in the current mode is
  refused before permission/execution; `get_current_mode` prompt-free with the
  governing mode's status text).
- **Sessions** — persistence across three restarts (`session/list` ->
  `session/load` with replay -> `session/resume` -> delete), `update_plan`
  persistence/replay.
- **Agent loop** — the `MAX_TURN_REQUESTS` cap + wind-down synthesis pass, auto
  provider routing (median cap, fail-open, disabled).
- **Web fetch** — `web_fetch` over a loopback JDK `HttpServer` (`WebFetchToolTest`):
  HTML conversion, JSON pass-through, paging + footer, declared-binary refusal,
  NUL sniff, gzip, redirects, SSRF block/opt-out, scheme refusal, oversize,
  strict args, HTTP error statuses, tool metadata; the redirect-hop SSRF
  re-validation (public host redirecting into a private host) is pinned against
  a ktor MockEngine in `WebFetcherTest` (no DNS/sockets; RFC 5737 IP literal);
  black-box e2e in
  `E2eWebFetchTest` (prompt-free plan-mode fetch with
  `ACP_WEB_FETCH_ALLOW_PRIVATE=1`, loopback refusal without it).

The **output caps** (bash/run tail truncation + progressive capture through a drain
timeout, `read_file` limit requirement, bounds, 20 MB size refusal, 2000-char-per-line
truncation and past-EOF error, listing caps, grep match/line caps and the
binary/oversized skips, the `.git` walker skip) are unit-tested in
`BashToolTest`/`ToolsTest`; move/delete semantics (destination-exists refusal,
symlink refusal, dir/file type mismatches, diff payloads, local-disk-only), the
whole-file diff convention and the fs-proxy diff skip, the strict JSON-null argument
rejections, and the MCP annotation mapping (trusted/untrusted `readOnlyHint`, kind
mapping, `title` annotation) are unit-tested in
`ToolsTest`/`PermissionAndFileStoreTest`/`McpBridgeTest`.

**e2e change policy**: e2e scenarios pin the agent's **wire contract** — protocol
message flow, security boundaries (permission routing, mode restrictions,
containment caps) and session/replay semantics — not internal orchestration (LLM
call counts, mock filler text, delta chunk splits). A behavioral change
(feature/fix) that requires updating an e2e assertion is expected: update it in the
**same commit** with a one-line comment, preferring a pin of equal strength. An e2e
failure without a behavioral change is a test-design smell: fix the test by
asserting the effect/shape, not the feature. Favor effect-based over exact-count
assertions (e.g. "load/resume make no LLM calls" rather than `equals(2, requestCount)`).

**Shell test suite** (`tests/bash/run-all`, run config `test_scripts`, Gradle task
`testScripts` wired into `check`): black-box tests for the **launcher composition** —
the JVM suites cannot see which argv/env the `ddr-acp-agent-docker` launcher composes.
The stub `tests/bash/stubs/fake-docker` is plugged in via the launcher's own
`DOCKER_BIN` seam (record mode: every invocation appended to `$FAKE_DOCKER_LOG`,
`FAKE_DOCKER_PULL_EXIT`/`FAKE_DOCKER_INSPECT_EXIT` drive the pull-fallback paths);
`tests/bash/harness.sh` is a zero-dependency assert lib (each test runs in a `set -e`
subshell, first failing assert aborts the test), `tests/bash/common.sh` provides the
launcher invocation (`run_docker_launcher <dir> [KEY=VALUE ...] [--skip-pull]`; env
assignments must precede flags and `ACP_DOCKER_EXTRA_MOUNTS`/`ANDROID_HOME`/
`ANDROID_SDK_ROOT` are always cleared).
Suites: `test_extra_mounts.bash` (`ACP_EXTRA_MOUNTS` derivation), `test_launcher_args.bash`
(sandbox flags, env forwarding, the API-key-never-in-env contract: no `--env
OPENROUTER_API_KEY=`, key file mounted ro, `0600` staged copy removed after the run,
`OPENROUTER_API_KEY_FILE` host file mounted without staging, `.env.local` masking, git
identity, pull fallback, Android SDK sharing: mount + pinned `ANDROID_HOME`/
`ANDROID_SDK_ROOT`, `ANDROID_SDK_ROOT` fallback, default-dir pickup, absent -> nothing,
`ACP_DOCKER_MOUNT_CACHES=0` disables, in-project SDK keeps the host path in the pins),
`test_error_paths.bash` (fail-loudly exits before any `docker run`, incl. the
set-but-unusable `ANDROID_HOME`/`ANDROID_SDK_ROOT` refusals). Fixtures live under
`build/` (never `/tmp`: the launcher skips extra mounts inside the container tmpfs) and
the API key is pinned to `sk-test` so a real key can never leak into logs.

Manual validation (the run configurations cannot do this):
- Docker: validate `bash -n ddr-acp-agent-docker` + `shellcheck ddr-acp-agent-docker build-docker`;
  build with `./build-docker` and smoke-test by piping an `initialize` request into
  `OPENROUTER_API_KEY=... ./ddr-acp-agent-docker --skip-pull` (expects a JSON-RPC
  response on stdout). What the shell suite cannot pin without a real daemon: the
  image itself (entrypoint, tool availability) and real bind mounts.
- Direct: validate `bash -n ddr-acp-agent` + `shellcheck ddr-acp-agent`; smoke-test the
  same way via `./ddr-acp-agent` (keep stdin open briefly after the request — closing
  it immediately races the transport teardown and swallows the response).

**Koog upgrade checklist**: on every Koog version bump, re-verify the three hand-rolled
surfaces against the new version (see the "No more Koog" bullet under Boundaries) and
update this file: (1) `GET /models` wire types - still `internal`? still without the
`reasoning` block? (2) chat request - does Koog's OpenRouter request model carry
`reasoning {effort}` now? (3) `agents-features-acp` - still batch-emits events, no
permission flow/modes/usage indicator/replay? The moment any of these closes, port that
surface to Koog.

**Definition of done**: a change is done when the `build` run configuration passes
(compile + all tests incl. the black-box e2e).

## Boundaries (do not silently change)

If you think a boundary is contra-productive, not valid anymore, overcomplicates or blocks necessary steps, clearly
communicate that with the user so we can review them.

- **Runtime**: the agent is an ACP **agent**, never the client/IDE, and never an MCP server.
- **stdout is reserved for ACP protocol (NDJSON)**. All logging goes to **stderr** (via
  kotlin-logging/SLF4J). Never `println`/log to stdout, or you corrupt the protocol (see
  Pitfalls). The ACP transport is the official SDK's `StdioTransport` over a
  `Flow<String>` from `System.in` and an output lambda writing to `System.out.println`
  (`Main.kt`).
- **`terminal/*` is deliberately skipped** - the agent runs its own local processes (`ProcessBuilder`), it does not
  delegate terminals to the client; the `terminal`
  client capability is ignored. This is a design decision, not a deferred feature.
- **Permissions are path-aware**: anything outside the session working directory -
  reads and writes alike - requires the user decision (mechanics under Features); do
  not execute a path-scoped file tool or mutating tool without the permission decision.
- **LLM provider**: OpenRouter. The **OpenAI wire types (from Koog) are the internal LLM
  contract** - a deliberate single-provider design decision; do not wrap them in
  domain/anti-corruption types. `LlmClient` in `net.dontdrinkandroot.acpagent.llm` owns
  only the transport (HTTP/SSE/JSON); both `llm` and `agent` (`AgentSessionImpl`) import
  the Koog wire types (`ai.koog.prompt.executor.clients.openai.base.models.*` for
  `OpenAIMessage`, `OpenAITool`, etc. and
  `.openrouter.models.OpenRouterChatCompletionStreamResponse`).
  - **No `temperature` is set** for chat completions (providers default it); pinned by
    `LlmRequestTest`.
  - Streaming is a hand-rolled line scan via `preparePost` + `bodyAsChannel().readLine()`
    (`data:` events, `[DONE]` terminator) that emits chunks as they arrive - not Koog's
    framework `LLMClient`/`ToolDescriptor` entry points. Koog's execute/streaming entry
    points are public since 1.1.1 (and streamed tool-call deltas decode fine - the
    partial-tool-call crash, JetBrains/koog#1996, is fixed since then); we hand-roll
    because (a) OpenRouter's `reasoning: {effort}` request param is not in Koog's
    `OpenRouterParams`, (b) every wire-shaped byte must round-trip through the shared
    `llmWireJson` (Koog clients carry their own `Json` with different settings), and (c) raw per-chunk access is needed
    for `delta.reasoning`, `usage` and finish
    reasons without Koog's StreamFrame conversion.
  - **No more Koog - for now (recheck periodically)**: hand-rolled surfaces beyond the
    wire types were re-evaluated against Koog 1.2.0 and stay hand-rolled:
    - `GET /models`: Koog's `OpenRouterModelsResponse`/`OpenRouterModel` are `internal`
      (module-private) and lack the `reasoning` block (`supported_efforts`,
      `default_effort`, `mandatory`) our thought-level config option needs - our
      `llm/LlmModels.kt` is strictly more capable.
    - Chat request: Koog's `OpenRouterChatCompletionRequest` still ships no `reasoning`
      field, so the `reasoning: {effort}` contract cannot be expressed on the client API.
    - `agents-features-acp` (added in 1.2.0) was read (`AcpAgent.kt`): it only works
      inside a full `AIAgent` framework rewrite and even then lacks per-token streaming (it batch-emits `toAcpEvents()`
      on LLM completion), the permission flow, session
      modes (plan/build/bash), the `usage_update` indicator and load-replay; it even
      carries `TODO: Support kind for tools`.
      This is **not a final decision** - Koog moves fast and the above gaps may close;
      re-evaluate the newer Koog version whenever it is upgraded (see the version bump
      checklist under Testing).

## Pitfalls and learnings

- **Anything on stdout corrupts the ACP NDJSON stream.** The only legitimate stdout write
  is the transport output lambda in `Main.kt`. A stray `println`, a chatty dependency, or
  a startup banner breaks the protocol. kotlin-logging 8.x prints an "initializing..."
  banner on first logger creation unless `KotlinLoggingConfiguration.logStartupMessage =
  false` is set first - `main()` in `Main.kt` does this (together with
  `org.slf4j.simpleLogger.logFile=System.err`) before `runAgent` creates the first
  logger; do not move a top-level `val logger = KotlinLogging.logger {}` ahead of it.
- **Koog wire JSON settings are load-bearing.** The shared `llmWireJson` (`llm/LlmWire.kt`)
  must keep `namingStrategy = JsonNamingStrategy.SnakeCase` (without it `tool_calls` /
  `finish_reason` silently fail to parse - verified e2e) plus `ignoreUnknownKeys = true`,
  `explicitNulls = false`, `encodeDefaults = true`. Do not create a second `Json` for LLM
  wire-shaped data with different settings; `LlmRequestTest` pins the request shape and the
  session store reuses the same instance for persisted history.
- **Stale e2e binary**: the e2e harness (`net.dontdrinkandroot.acpagent.e2e`) drives the installed launcher, and `test`
  depends on `installDist`. Running a single test from the IDE against an old
  install validates stale sources - re-link (`installDist`) first.
- **Gradle build cache can report a false green**: outputs are cacheable, so a
  `test`/`build` run that *just changed test sources or the classpath* may come
  back `FROM-CACHE`/`UP-TO-DATE` and hide failing tests (observed: a new
  `WebFetcherTest` failing but every `test`/`build` invocation "passing" from
  cache because an earlier run had stored the up-to-date-looking output). When a
  run reports no executed test task (`test FROM-CACHE` / `UP-TO-DATE` with no
  `:test` execution line), distrust it: rerun with `./gradlew clean test
  --tests <Class>` or `--rerun-tasks` before believing a green. Conversely a
  fresh-looking `test` line means it really ran.
- **ktor client-side redirect plugin vs. the manual redirect loop**: any client
  used with `WebFetcher` (production `defaultClient` and every test client) must
  set `followRedirects = false`. The plugin is on by default, consumes 30x
  responses itself, and silently bypasses the per-hop SSRF re-validation under
  test (a MockEngine test without it followed the redirect and the loop never
  saw the `Location` header).
- **JDK chunked `InputStreamReader` can spin forever**: a read-loop that calls
  `reader.read(chunk)` and processes chars incrementally (the first
  `StreamingLineReader` implementation) was observed to burn 100% CPU forever on
  zero-char reads under JDK 25 (`jstack` on the Gradle test worker showed
  `RUNNABLE` in `StreamDecoder.read`), non-deterministically. The file line
  reader therefore splits on `\n` at the **byte** level and decodes per line -
  avoid incremental `InputStreamReader` decode loops in this repo; if a test
  "hangs" without forking a worker, take a `jstack` of the test worker before
  blaming Gradle.
- **CIO engine request timeout**: the ktor CIO engine applies a default **15s aggregate
  `requestTimeout`** over the whole HTTP call unless it is explicitly disabled
  (`engine { requestTimeout = 0 }`). This silently kills any LLM chat completion
  that streams or thinks for more than 15 seconds total (`Request timeout has
  expired [url=..., request_timeout=unknown ms]` - it looks like a server error but
  is client-side). Only requests made through the SSE plugin are exempt; setting
  `Accept: text/event-stream` on a plain request is not enough. `LlmClient` disables
  it and guards with a 120s socket idle timeout instead (`endpoint { socketTimeout }`).
- **SDK session state reporting**: the SDK's `asModeState()` builds the session
  response from `defaultMode` + `availableModes` (not the live `currentMode`),
  so a restored session must set `defaultMode` to its persisted mode.
- **Client-side error surface**: the official SDK client rethrows JSON-RPC error
  responses as `AcpExpectedError` (message only, not `JsonRpcException` with the
  agent-side code); agent-side direct calls (unit tests) see `JsonRpcException`
  for `jsonRpcInvalidParams`.
- **Shutdown**: do not drive shutdown from the transport's `onClose`; `runAgent` polls
  `transport.state.value == Transport.State.CLOSED` and then calls `protocol.close()`
  (`Main.kt`).
- **Gradle lock timeout**: `Timeout waiting to lock file hash cache ...` means another
  build/daemon holds the lock (the error names the owning PID). Wait and retry - never
  kill processes or delete lock files.

## Recipes

- **Connect an MCP server** - all three transports are verified on JVM (`mcp/McpConnector.kt`): stdio via
  `ProcessBuilder` + kotlinx-io
  `asSource()/asSink().buffered()`; streamable HTTP and classic SSE via
  `HttpClient(CIO){ install(SSE) }`.
- **Add a tool** - implement `AgentTool` (in `tools/Tool.kt`: `name`, `description`,
  JSON-schema `parameters`, `kind`, `mutating`, optional `modes`, `execute`) and register
  it in the `ToolRegistry` in `Main.kt` (the per-session registry copies the local one).
  `mutating` tools automatically go through the permission flow; tools with restricted
  `modes` are filtered per mode by `ToolRegistry`.

## References

* [Agent Client Protocol](https://agentclientprotocol.com/llms.txt)
* [Model Context Protocol](https://modelcontextprotocol.io/llms.txt)
* [OpenRouter](https://openrouter.ai/docs/llms.txt)
* [Kotlin Coding Conventions](https://raw.githubusercontent.com/JetBrains/kotlin-web-site/refs/heads/master/docs/topics/coding-conventions.md)
* [Koog Documentation](https://github.com/JetBrains/koog/tree/develop/docs/docs)

## Self-Update Instruction

This guidelines file is a living document and MUST be actively maintained by the LLM Agent.

* **Trigger:** Whenever significant changes are made to the project that are related to any information provided here,
  the LLM Agent MUST immediately update this file (`AGENTS.md`) to reflect the current state of the project.
* **Content:**
  * Add any information that could have helped the agent to solve the task more efficiently or in fewer steps.
  * Remove outdated, obsolete, or incorrect information.
  * Ensure all tech stack versions and library names are accurate.
  * Make sure the most important features are clearly documented.
  * Keep the project structure up to date so that the most important files and directories are visible at a glance.
* **Proactivity:** Do not wait for explicit instructions to update these guidelines if you identify a discrepancy
  between the guidelines and the actual codebase.
