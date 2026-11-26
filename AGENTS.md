# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

This repository (`acp-agent.kotlin`) holds `acp-agent`, a coding agent that talks to JetBrains
IDEs (IntelliJ IDEA, PhpStorm, etc.) exclusively over the **Agent Client Protocol (ACP)** - a
JSON-RPC 2.0 / NDJSON protocol over stdio. There is **no GUI/TUI**; the agent is launched by the IDE as a
subprocess and driven via `stdin`/`stdout`.

The agent acts strictly as an ACP **agent**: it only *consumes* ACP, never acts as a
client/IDE, and never exposes an MCP server - it only *consumes* MCP servers.

The target platform is Linux only.

## Tech stack

JVM-only application in a single module (`kotlin("jvm")` + `application`). Main sources in
`src/main/kotlin`, tests in `src/test/kotlin`. All owned versions are centralized in the
version catalog `gradle/libs.versions.toml` (single source of truth; declared versions match
what actually resolves).

| Layer            | Dependency                                                                              | Version                      |
|------------------|-----------------------------------------------------------------------------------------|------------------------------|
| Kotlin / plugins | `kotlin("jvm")`, `kotlin("plugin.serialization")`, `application` (JDK 25, Gradle 9.8.2) | 2.4.21                       |
| Dep update check | `io.github.ben-manes.versions` (project plugin via catalog; `dependencyUpdates`)        | 0.67.0                       |
| ACP              | `com.agentclientprotocol:acp`                                                           | 0.34.0                       |
| MCP              | `io.modelcontextprotocol:kotlin-sdk-client`                                             | 0.16.0                       |
| HTTP             | ktor client (CIO engine)                                                                | 3.6.1                        |
|                  | ktor-client-encoding (gzip/deflate for `web_fetch`)                                     | 3.6.1                        |
| HTML parsing     | jsoup (HTML -> line-based text for `web_fetch`; zero runtime deps)                      | 1.23.2                       |
| Coroutines       | kotlinx-coroutines-core                                                                 | 1.11.0 (declared + resolved) |
| IO               | kotlinx-io-core                                                                         | 0.9.2                        |
| JSON             | kotlinx-serialization-json                                                              | 1.12.0 (declared + resolved) |
| LLM wire models  | owned (`llm/models/ResponsesWireModels.kt`, Responses API, no LLM framework)            | -                            |
| UUIDv7 ids       | com.github.f4b6a3:uuid-creator (zero deps; `UuidCreator.getTimeOrderedEpoch()`)         | 6.1.1                        |
| Logging          | kotlin-logging via slf4j-simple                                                         | 8.0.4 / 2.0.20               |

kotlin-logging 8.0.4 is declared explicitly because ktor/ACP pull 7.0.0 and 8.0.01
transitives; pinning 8.0.4 pins the banner-suppressing API.

## Boundaries (do not silently change)

If a boundary looks contra-productive, overcomplicated or blocking, say so instead of working
around it.

- **stdout carries the protocol.** Every byte must be an ACP JSON-RPC line; all logging goes to
  stderr. A stray `println`, a chatty dependency or a startup banner corrupts the stream (the
  actionable half is under Pitfalls).
- **`terminal/*` is not implemented on purpose.** The agent runs its own processes (`ProcessBuilder`) instead of
  delegating to the client's terminal and keeps ignoring the
  `terminal` capability. A design decision, not a deferred feature.
- **Permissions are decided by path, never by trust.** A path-scoped or mutating tool runs only
  when `permissionDecision` returns ALLOW: inside the cwd, inside the matching directory tier or
  - for reads - the scratch dir. `unlistedPaths = deny` refuses instead of prompting.
- **One LLM provider:** OpenRouter's Responses API. `llm/models/ResponsesWireModels.kt` is the
  internal LLM contract - no domain/anti-corruption wrapper, `LlmClient` is transport only.
- **Wire items stay raw.** History items are `JsonObject`s, re-typed only when the typed value
  re-encodes to the identical object: a restored session replays byte-for-byte, and a new wire
  member can never be dropped by a parser branch. Consumers read an item's meaning, not its
  variant.
- **Reasoning is live-only.** Within a turn it is replayed; every exit path clears it (cancellation included, where no
  terminal branch runs) and it never reaches the record.
- **The request shape is a negative contract:** no `temperature`, `max_output_tokens`, `store`,
  `previous_response_id`, `include`, `reasoning.context` (pinned by `LlmResponseGoldensTest`).
- **No session-record migration.** A record from another dialect still decodes but fails at its
  first turn; do not add a compat shim.

## Conventions

* We always adhere to Clean Code principles:
  * **Meaningful Names:** Choose names that reveal intent. A variable or function name should explain why it exists and
    what it does without needing a comment.
  * **Functions Should Do One Thing:** Functions should be small and perform a single task. If you can extract a subset
    of a function into another, it’s doing too much.
  * **DRY (Don't Repeat Yourself):** Eliminate duplication. Every piece of logic should have a single, unambiguous
    representation in the codebase.
  * **Comment on "why" not "how":** Code should explain itself. Use comments only when you cannot express intent through
    code or to explain "why" (business logic), never "how." Make sure the "why" is always clear so when looking at the
    code again the intent is clear either through the code or comments.
  * **The Boy Scout Rule:** Leave the code slightly better than you found it, but keep changes related to the requested
    task.
* We always adhere to SOLID principles:
  * **Single Responsibility (SRP):** A class should have only one reason to change.
  * **Open/Closed (OCP):** Software entities should be open for extension but closed for modification.
  * **Liskov Substitution (LSP):** Subtypes must be substitutable for their base types.
  * **Interface Segregation (ISP):** No client should be forced to depend on methods it does not use.
  * **Dependency Inversion (DIP):** Depend on abstractions, not concretions.
* We use test-driven development by default, in small red→green→refactor cycles writing tests first.
  Do not write multiple tests at once, always one test, then implementation, then refactor.
  The tests are documenting our specification and expectations.
  If a test would be overly complicated, ask the user first if it is worth it.
* Use Kotlin sugar to make the code more readable.
* When fixing bugs add regression tests if reasonably possible.
* `_meta: JsonElement?` is threaded through every ACP model type exactly as the SDK does.
* **Never commit without the user requesting it**: the agent must not run
  `git commit` (or otherwise write to git history, e.g. `git push`, `git tag`)
  unless the user explicitly asks. The `git` run config is for read-only
  inspection (`status`, `diff`, `log`) unless the user requested a mutating
  git command.
* **Commit messages are always a single line**: `<type>(<scope>): <summary>`,
  optionally ending in `(closes #N)` / `(fixes #N)` — no body, no trailing
  paragraphs (e.g. `fix(tools): keep .git out of tree-mutation diff evidence (fixes #79)`).

## Building / running

Dev loop: `./gradlew compileKotlin` / `compileTestKotlin` -> `test` (unit + black-box e2e) ->
`build` (the definition of done). Drive it through the `run` configurations: they wrap these and
`.ai/scripts/show-test-failures.sh` in `timeout` (180s for the fast loop and single test classes,
300s for `build`/`test` incl. a cold daemon), so a hung daemon surfaces as a timeout. The
configurations are **not listed here** - they are rendered into the system prompt from
`<cwd>/.ai/run.json` per turn, so `run` is the source of truth; manage them with
`list_run_configs` / `create_run_config` / `update_run_config` / `delete_run_config`.
`system_prompt` runs `printSystemPrompt` (`agent/SystemPromptDump.kt`).

Launch paths (checked in, the only supported ones; the only argument is `--profile <name>`,
everything else comes from the selected `config.json`):

- `bin/acp-agent` — direct (no sandbox): `installDist`, then execs the agent
- `bin/acp-agent-sandbox` — `installDist`, ensures the image (`--rebuild` forces a local build; a
  manual `docker pull` updates it, the wrapper never pulls), then execs the Kotlin supervisor (byte relay over the
  container's stdio; `--profile` is parsed on both sides)

Docker is resolved from `PATH` everywhere; an IDE-inherited `PATH` without docker is a hard
failure. The `sources` configuration (`./gradlew unpackLibrarySources`) unpacks the `-sources`
jars of the runtime + test classpath into `build/library-sources-unpacked/` - the first stop for
"how does library X behave" questions, before web research (`Sync` tasks, so stale versions are
wiped on bumps).

**Shutdown**: `runAgent` waits for `Transport.State.CLOSED`, then cancels pending incoming
requests (a client closing stdin never sends `session/cancel`), flushes live sessions (`persistAllSessions`; the
cancelled turn's own persist races the JVM exit), then closes the
protocol. Deliberately not driven from the transport's `onClose`.

## Tool Usage

* **Web research**: use the `web_search_exa` and `web_fetch_exa` tools for research, validating information and
  looking things up when unsure — do not guess. Search first with `web_search_exa`, then fetch the full page with
  `web_fetch_exa` when highlights are insufficient. Verify API contracts, library versions, spec details and
  upstream behavior against primary sources before relying on them; cite the sources you checked in your summary.
* **GitHub issues**
  * Always set the **issue type** (`Bug` / `Feature` / `Task`).
  * Every **bug** additionally gets the **`Priority`** field (`Low`, `Medium`, `High`, `Urgent`)
  * Avoid redundant labels (like "bug" for a bug).
  * **Proactively create issues for newly discovered bugs** — file them, don't just mention them.
  * GitHub issues are the sole issue tracker.
  * When adding an issue, make sure it is not duplicated.
  * This is a public repo, so make sure no sensitive information is exposed.

## Layout

The package is the map - read `net.dontdrinkandroot/acpagent/<package>` before trusting any
summary here; behaviour and rationale live under Features below, one-off detail in KDoc.

- `Main.kt`, `BuildInfo.kt` - `main` (logging setup, strict argument parsing), `runAgent`
  (transport, registries, session factory, shutdown flush), build hash + agent version.
- `config/` - strict `config.json` parsing, `--profile` selection, the project overlay,
  key-file resolution (`Secret`), the XDG state home.
- `llm/` - the Responses-API transport (hand-rolled SSE line scan), the owned wire models, the
  `ChatCompleter` seam.
- `providerrouting/` - auto routing (throughput sort + median cap) and the provider option's
  endpoints cache.
- `agent/` - the session itself: record/store, state, mode machine, prompt runner, tool-call
  executor, system prompt, permission policy, replay, `/info`.
- `mcp/` - MCP consumption (stdio, streamable HTTP, classic SSE) and annotation-driven tool
  bridging.
- `sandbox/` - the supervisor: host facts -> typed plan -> argv composition, `acp-agent-sandbox`
  entry point.
- `tools/` - the tool set + `ToolRegistry`/`ToolCatalog`, `FileStore`, the walkers, output caps,
  the exclusion and diff policies.
- `src/test/.../sandbox/` - the argv pin table (`SandboxPinRows`), plan/fail-loudly/supervisor/
  Dockerfile tests and `FakeDocker`.
- `src/test/.../e2e/` - the black-box harness (`E2eAgentTest`) and its mocks (`MockOpenAiServer`, `MockMcpServer`,
  `MockMcpSseServer`, `StdioMcpMockServer`).
- `bin/`, `build-docker`, `Dockerfile` - the two launch wrappers, the local image build, the
  multi-stage image (`build-image.yml` pushes it to GHCR).
- `.ai/run.json`, `.ai/scripts/` - the run configurations and `show-test-failures.sh`.
- `README.md` - user-facing docs (install, IDE setup, configuration, troubleshooting).

## Features

### Sessions & persistence

- **Session cwd gate**: the `cwd` of `session/new`/`session/load`/`session/resume`
  is validated by `sessionCwdProblem` (`agent/SessionCwd.kt`: absolute, existing, a directory -
  an unparseable path is a clean refusal, not a throw) and refused with `-32602` before a
  session exists, in `AgentSupportImpl` (the same layer that already answers invalid ids and
  the load/resume cwd mismatch; on load/resume the check runs *after* the record lookup, so an
  unknown id keeps its own refusal text). The cwd *is* the session's file context - every path-scoped
  tool resolves against it, and `bash`/`run` and stdio MCP children start there - so without
  the gate a session came into existence and failed per tool call, while a stdio MCP server
  silently inherited the agent's own directory. Only a gate, no canonicalization: the value
  stays the session's identity (persisted and compared verbatim), so symlinked or
  differently-spelled paths are deliberately not folded. Consequence, deliberately accepted:
  a session whose project directory is gone can no longer be loaded/resumed (`session/list`
  and `session/delete` still work, so cleanup stays possible). `AgentSupportImpl` takes the
  check as a constructor seam (`cwdProblem`, default `::sessionCwdProblem`) so lifecycle
  tests can drive synthetic cwds; production always checks the filesystem. Pinned by
  `SessionCwdTest` (unit), `SessionLifecycleTest` (`createSession`, `loadSession` and
  `resumeSession` refuse, the record survives a refusal) and `E2eSessionPersistenceTest`
  (black-box `-32602`, no record left behind).
- **Lifecycle & persistence**: `initialize` -> `session/new` (random `sess_` + 16 hex digits) ->
  `session/prompt` -> `session/delete` (closes MCP connections + `LlmClient`, removes the
  record); `session/cancel` is coroutine cancellation. **An ended session refuses work**:
  `session/delete` on an active session ends it (record removed, resources
  closed, live-map entry dropped), but the SDK keeps routing `session/prompt`/set-mode/
  set-model/set-config-option to our instance - so `AgentSessionImpl.ensureActive()` refuses
  every one of them with `-32002` (`JsonRpcErrorCode.RESOURCE_NOT_FOUND`, the spec's code for
  later requests against a deleted session) instead of running a turn whose persistence is
  silently dropped (`SessionState.persist()` no-ops once deleted) while still spending tokens.
  `close()` and `delete()` stay callable and idempotent, as the spec requires. Known SDK
  deviation: a `session/close` of a session the SDK no longer tracks answers `-32602`
  ("Session … not found") instead of the spec's idempotent empty result - the SDK's handler
  rejects before reaching us (`Agent.getSessionOrThrow`), so it cannot be fixed on our side.
  Sessions persist to
  `$XDG_STATE_HOME/ddr-acp-agent/sessions/<sessionId>.json` (fallback `~/.local/state`),
  atomically (temp + move) on every completed turn and mode/config change, **after every
  executed or denied tool call** (`ToolCallExecutor` -> `state.persist()`, so a crash mid-turn
  keeps the effects-so-far visible in the record), **when a turn
  is interrupted** (`prompt`'s `finally` persists under `NonCancellable` when the turn did not
  complete - `session/cancel` or a closing client, so the user message, the streamed partial
  reply and the applied tool calls survive; the record is pair-complete via the pairing
  repair), **on `session/close`**
  (same `NonCancellable` flush before the resources go away) and **at
  shutdown** (`AgentSupportImpl.persistAllSessions()` after the transport closed, see
  Building / running). **Crash-orphaned temp files are swept**: the `finally`
  of the atomic write covers in-process failures only - a SIGKILL between write and move
  strands `session-*.tmp` files (0600, holding full history, invisible to `list()`).
  `save()`/`list()` therefore run `sweepStaleTempFiles`
  (`tools/AtomicWrite.kt`) at most once per `STALE_TEMP_AGE_MILLIS` (1h - `save` runs after
  every tool call, an unconditional scan would rescan the same debris on every persist; the
  throttle still self-heals against debris of crashed sibling processes sharing the sessions
  dir) and deletes only regular `<namePrefix><random>.tmp` files last modified more than 1h
  ago - a concurrent writer's fresh temp, symlinks and other names are untouched (the age
  gate is load-bearing: several agent processes share one sessions directory).
  `writeAtomically` sweeps the target's stale temps on every call (unthrottled on purpose:
  its only caller is the rare `.ai/run.json` write, so `run.json*.tmp` debris in the project
  dir is cleaned the same way). The record (`agent/SessionRecord.kt`) holds history (Responses-API
  wire items as raw `JsonObject`s from `llm/models/ResponsesWireModels.kt` via the shared
  `llmWireJson`), mode, title
  (first user message, 72 code points), model, reasoning, plan and
  `toolOutcomes` (toolCallId -> `"completed"` | `"failed"`, recorded at history-append time in
  `SessionState.appendToolResult`; lenient at replay - unknown values and absent entries fall back
  to COMPLETED). Persist and delete share a `persistMutex` so a delete never resurrects the
  file; failures log to stderr and never fail the turn. Client-supplied ids are format-checked
  (`isValidSessionId`) so traversal/separators never reach the filesystem. Session creation
  (and restore) closes any already-opened MCP connections and the `LlmClient` when the model
  feed fetch fails. **Tool-call pairing invariant**: a cancelled turn leaves the
  call it was running in the history without a result, and a strict provider (Azure/OpenAI)
  rejects any later chat request with 400 "No tool output found for function call <id>".
  The invariant is therefore enforced at the **observation
  boundary, not at the write sites**: `repairToolCallPairing`
  (`agent/ToolCallPairing.kt`, pure + idempotent) closes every assistant tool-call id that has
  no matching `role:"tool"` result with one synthetic FAILED result ("Tool call was
  interrupted before a result was produced; any effects may already be applied."), inserted
  after the batch's already-present results, and runs inside `SessionState.historySnapshot` /
  `replaySnapshot` / `buildRecord` — so the LLM request, the load replay and the persisted
  record are always pair-complete while the in-memory history is never mutated by the repair (an
  interrupted call reaches disk pair-complete at the turn's cancel persist, the close persist or
  the shutdown flush). One warn log per snapshot keeps the dangling state visible on stderr.
- **Restore**: `initialize` advertises `loadSession` + `sessionCapabilities.list/delete/resume/close`.
  `session/load` reconnects MCP servers and replays history (user/agent chunks, pending
  `tool_call` + terminal `tool_call_update` with the persisted real outcome - denied,
  disabled, unknown and failed calls replay as FAILED, successes as COMPLETED - and plan) from
  `AgentSession.replayHistory()`, which the SDK runs **before** the load response - the order
  the spec requires; the pure mapping is
  `buildReplayUpdates` (`agent/ReplayUpdates.kt`, unit-pinned by `ReplayHistoryTest`), and the
  ordering is pinned black-box by `E2eSessionPersistenceTest`, which asserts the updates are
  already delivered when `loadSession` returns (and therefore uses the SDK's early-delivery
  operations overload instead of the buffering factory);
  `session/resume` restores without replay. `session/list` filters by cwd, sorts by recency (on
  the raw epoch millis), skips corrupt records; the SDK pages the result (10 entries per page +
  `nextCursor` over our `Sequence` override). Its `updatedAt` is rendered by
  `formatSessionUpdatedAt` as `uuuu-MM-dd'T'HH:mm:ss.SSSxxx` in the system zone (`2026-09-23T15:37:02.123+02:00`,
  `+00:00` on a UTC host - never a `Z` suffix, never
  the shortened `toString()` forms that drop seconds/millis), so clients that render it
  as wall time do not shift it by the local offset (`AgentSupportImpl` takes the
  `ZoneId` as a constructor parameter, defaulting to `ZoneId.systemDefault()`, so the
  rendering is unit-pinned with a fixed zone). **Open-session marker**:
  `SessionInfo` has no liveness field and the spec forbids adding one at the root of a spec
  type, so `session/list` marks the sessions open in this process with
  `_meta = {"net.dontdrinkandroot/active": true}` (`ACTIVE_SESSION_META` in
  `agent/AgentSupportImpl.kt`); its absence means "not open here". The live map is the
  single source of liveness and self-cleaning: `AgentSessionImpl` fires a one-shot
  `onSessionReleased` callback when the session ends - `session/close`, `session/delete`, or
  a failing `replayHistory` (the SDK drops its wrapper there, so the session would otherwise
  stay "already active" and leak its MCP/LLM resources) - and `AgentSupportImpl.register`
  uses it to remove the entry value-conditionally. A closed session therefore loses its
  marker and its id becomes `session/load`-able again. The derived title is announced to the client:
  the prompt flow emits one `session_info_update` (title only - `updatedAt` changes on every
  persist and would be chatty) right after the user message is appended, i.e. inside the
  turn's own update stream rather than racing the prompt response that closes the client's
  channel. `SessionState.takeTitleAnnouncement` derives the title exactly like
  `buildRecord`, compares it against `announcedTitle` and marks it announced, so each title
  is sent exactly once: a restored record's title counts as already announced (load/resume
  responses and `session/list` carry it, so a loaded session does not re-announce), and a
  record without a title announces at its next prompt. Unit-pinned by
  `SessionStateTitleTest`, black-box by `E2eSessionPersistenceTest`.
  Cwd mismatch, unknown/invalid ids and
  double-loads are invalid-params; corrupt records are internal errors.

### Modes & agent loop

- **Agent loop**: up to `tools.maxTurnRequests` tool-calling LLM iterations per prompt (default 100,
  rejected below 1 when the file is parsed); text streamed as `AgentMessageChunk`,
  tool-call deltas merged, results appended to history; a turn stops on `END_TURN` (no tool call) or,
  when the iteration budget is exhausted while the model kept calling tools, streams one final
  text-only synthesis pass (tools omitted from the request) that summarizes what was done and what
  remains, then ends with `MAX_TURN_REQUESTS`. Tool calls run sequentially. **Immediate-repeat
  guard**: `ToolCallExecutor` refuses a tool call that is raw-identical (tool name +
  `function.arguments` string, compared verbatim, not parsed) to the immediately
  preceding *executed* call of the same turn, but only once that identical call has already
  produced the identical result twice in a row (result = error flag + result text, the
  model-visible outcome; the client-facing diff is excluded) - so the error fires on the *third*
  identical call, with a FAILED result naming the repetition and the raw arguments ("A third
  identical call cannot yield new information"). A single identical
  repeat always executes (a retry can legitimately reveal a new result - flaky command, changed
  state), and a changed result resets the streak. The refusal never
  reaches permission/execution, records `failed` (replay/pairing-safe), leaves the streak armed (4th+ identical calls
  keep failing) and is cleared at every
  turn start (`onTurnStart` from `PromptRunner.run`) so a legitimate identical call in a later
  turn always runs. Conservative by design: a formatting variant of the same parsed arguments (spacing/key order) is NOT
  a repeat, and an alternating A/B cycle (e.g. `limit=70`, `limit=75`,
  `limit=70`) stays unguarded - that needs the full per-signature fix still tracked by #39.
  The session is a thin
  SDK facade over focused components: `SessionState` (mutable state + record lifecycle),
  `ModeState` (the mode machine incl. deferred switching),
  `SystemPromptBuilder`, `SessionConfigOptions` (config option strategies), `ToolCallExecutor`
  (one tool-call lifecycle: mode gate -> unknown tool -> repeat guard -> permission -> execution
  -> updates) and `PromptRunner`
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
  conversation history instead — modal status messages (a `system`-role message item,
  LLM-internal, never rendered by the client), each stating the mode's semantics ("Mode: ... Read-only /
  Read-write / ...") plus the tools available in it (`ToolRegistry.availableForMode`). **The mode
  status message has a single writer: prompt start** (`SessionState.indicateCurrentMode`, called
  from the prompt flow before the user message is appended): it appends the message iff
  the current mode differs from the last *indicated* one, so the model sees exactly one message per
  mode that actually governs a turn. No session-start seed (a fresh session states the mode at its
  first prompt), no message on the switch itself: idle switches only flip `currentMode` (+
  immediate client notification, never deferred), mid-turn switches apply at the turn-end flush,
  and both write their message at the *next* prompt start - N flips without submitting coalesce
  into one message for the latest mode, and a flip back to the already-indicated mode writes
  nothing. Restore parses `lastIndicatedMode` from the trail's last `Mode: <id>.` system message
  (`SessionState.lastIndicatedTrailMode`), so a switch applied after the last message (e.g. a
  turn-end flush persisted, then a crash before the next prompt) self-heals at the next prompt
  start; a record without mode messages gets one message at its next prompt
  instead of staying silent forever. **Mode switches are deferred**: a `mode` request while a
  prompt turn is running only records a pending mode in `ModeState`
  (`SessionState.mode.requestMode`, latest wins) and is applied as a single flush when the turn
  hands control back (`SessionState.mode.flushPendingMode()` in the prompt flow's try, before
  `setPromptActive(false)`), with one `current_mode_update` at that point (the flush flips the
  mode only - its status message waits for the next prompt start); the `finally` drops whatever
  is still pending and clears `promptActive` (so a cancelled turn never leaks its requested mode
  into the next turn), and `notifyModeState` is suppressed only while a mode is pending (`hasPendingMode`) so mid-turn
  model/reasoning switches still notify immediately. Idle switches
  still apply immediately. The prompt is still rebuilt per turn so mid-session
  `create_run_config` / `AGENTS.md` edits apply (it carries
  `cwd`, today's date, the available run configurations (name + command +
  description, re-read from local disk per iteration) and `Agent build: <sha>[-dirty]`).
  A `mode` config option (`session/set_config_option` + `set_mode`) echoes
  the *governing* mode until the flush; unknown -> invalid-params.

### Run configurations & build hash

- **Run configurations**: the `run` tool (every mode, `tools/RunTool.kt`) executes a
  configuration from `<cwd>/.ai/run.json` (`{"name": {"command": "...", "description": "..."}}`,
  read from local disk every access, fail-open like AGENTS.md). The tool's description is a static,
  location-agnostic string; the available configs (name + command + description) are surfaced in
  the system prompt instead. Commands are shell strings run
  via `ProcessRunner`; the model's `args` are an **array of strings** delivered as **positional
  parameters** at the command's `[args]` slot (one element = one argv token, never
  shell-interpreted; a raw `{args}` string splice would let a model-controlled
  `args` value execute arbitrary shell prompt-free even in plan mode - hard switch: a config
  still carrying a `{args}` placeholder does not substitute and must be migrated;
  quoting around the slot, e.g. `--tests "[args]"`, silently degrades: the quotes concatenate
  to nothing and the expansion loses its quoting, so `'a b'` word-splits and `*` globs - the
  shipped `test_class` uses `--tests [args]` for exactly this). Every `[args]`
  occurrence becomes a quoted `"$@"` (so elements with whitespace stay
  one token) and the command runs as `/bin/sh -c '<command>' sh <args...>`; configs without the
  slot reject non-empty args (empty arrays pass). `mutating = false` so `run` never asks for
  permission in any mode (incl. plan); the trust model is that the config file is
  project-controlled (same trust tier as AGENTS.md). The `title` override (`run(config: ...)`,
  see the tool-call title note under Permissions) still renders in tool-call progress.
  Configs are managed by explicit dedicated tools (`tools/RunConfigTools.kt`, also read from
  local disk): `list_run_configs` (read-only, every mode) and `create_run_config` /
  `update_run_config` / `delete_run_config` (mutating, build/bash only, so plan stays
  read-only). `update` is field-level - a blank `command` is always rejected (never cleared),
  an omitted field stays unchanged, an empty-string `description` clears it; create rejects
  existing names and blank commands; delete/update reject unknown names. All three write
  tools reject a blank name with the same clean error result ("Run configuration name must
  not be blank" via `RunConfigException` -> `runConfigResult`, never a raw
  `IllegalArgumentException` escaping `execute()`). Writes are atomic (temp + move) and refuse to
  touch a corrupt/unparseable file; unknown entry fields
  round-trip untouched. These dedicated tools are the **only** writers of the file: `.ai/run.json`
  is a default file-access exclusion, so `read_file`/`edit_file`/`write_file`/`delete_file`
  refuse it and `list_dir`/`glob`/`grep` hide it (the permission prompt of the
  write tools must not be bypassable by an in-project `edit_file`; see File-access exclusions).
  The repo ships a default `.ai/run.json` with the standard dev loop (the loop itself, the
  `timeout` wrapping and the project wiring are under Building / running).
- **Build hash**: `generateGitProperties` writes `git.properties` (`git.commit=<sha>[-dirty]`,
  `unknown` outside git) into resources; `BuildInfo.kt` reads it. Docker injects it via the
  `GIT_SHA` build-arg (no `.git` in the build context).

### Model, reasoning & provider options

- **Model + reasoning + provider options**: `session/new`/`load`/`resume` fetch the OpenRouter model feed
  (`llm/LlmModels.kt`:
  tool-capable text-output models, sorted; failure fails session creation).
  Per-session `model` (default `openRouter.defaultModel`), `reasoning` effort (`category: thought_level`; empty
  `supported_efforts` -> gateway levels max..minimal;
  mandatory models drop `none`; `none` omits the request field) and `provider` (category `provider`)
  config options. Model switches reset reasoning and provider; changes emit updates and persist. The request
  carries `reasoning: {effort}` (own wire type `ReasoningRequest` in `ResponsesWireModels.kt`).
  - **Provider option**: flat select with `auto` (real value, default, always re-selectable) +
      the current model's provider slugs from the endpoints feed (value = endpoint `tag`, name = `provider_name`;
      blank tags and a tag literally `auto` are skipped - `auto` always means OpenRouter routing);
      description "Provider used for the selected model (auto = OpenRouter routing)". A manual pick sends
      `provider: {order: ["<slug>"]}` only (no `max_price` cap, OpenRouter fallbacks kept); a slug not listed by
      OpenRouter is harmless (the router just falls back). `auto` is env-aware: with auto routing enabled the request
      carries the `sort: "throughput"` + median-cap preferences; with
      `openRouter.autoThroughputSorting: false` no `provider` field at all - the toggle gates the auto policy
      only, the option stays visible and a manual slug always wins over it. **Fail-open lifecycle**: the option is
      backed by a snapshot of the current model's providers, seeded at session assembly (`assembleSession` calls
      `providersFor` for the initial model and passes `initialProviderOptions` - failure/empty feed logs a warn and
      hides the option, unlike `fetchModels`, whose failure aborts session creation) and re-read on model switches
      (`refreshProviderOptions` in `AgentSessionImpl`); the option is hidden entirely whenever no feed is
      available (mirroring how `reasoning` hides without a reasoning block). A **persisted manual pick keeps
      applying on the wire** while the feed is down (an explicit decision outlives the picker being hidden); only
      when the feed is *available* and no longer lists the stored slug does the selection self-heal to `auto` with a
      warn (stale slug, checked at snapshot seed/refresh). Persisted as `SessionRecord.provider` alongside
      `model`/`reasoning`, restored on load/resume; reset to auto on model switch (slugs are per-model). Pinned by
      `SessionConfigOptionsTest`/`SessionStateProviderSelectionTest` (unit) and `E2eProviderOptionTest` (e2e).

### Planning & usage

- **Plan updates**: `update_plan` (`tools/PlanTool.kt`, kind `think`, every mode) emits ACP
  `PlanUpdate` and stores entries for persistence/replay; decoded into the SDK's typed
  `PlanEntry` (strict enums - deliberate deviation from the Go raw-string passthrough). **Prompt guidance**
  (`agent/SystemPrompt.kt`: a mode-independent sentence after the modes table
  plus the `### Plans and progress` rule group): the plan is stated as available in every mode and
  outliving mode switches and turns (a plan started while researching keeps governing in build/bash);
  trigger = three or more distinct steps, many tool calls or an explicit user request for a plan,
  skipping single-step work and padding;
  creation as soon as the shape of the work is clear ("never only at the end of the turn"), with the
  mode-specific role (in plan mode the settled plan is the hand-back, in build/bash the executed
  checklist); whole-list replacement (every call replaces the plan, no unchanged re-sends); one
  `in_progress` entry, statuses flipped as work happens (no batched completions); structural revision
  (add/remove/split/reorder) explicitly sanctioned, a stale plan named a defect; completed entries
  consolidated into one outcome entry so the plan stays a short progress log; the plan doubles as a
  scratchbook for decisions/blockers; closure requires nothing left `in_progress`. The prompt states
  there is no `blocked`/`cancelled` status (ACP's `PlanEntryStatus` is `pending|in_progress|completed`),
  so blockers go into the reply. `### Communication` additionally forbids restating a just-sent plan (the client renders
  it). Pinned by `SystemPromptTest`; the tool description carries the same
  replacement/one-in_progress contract (its `parameters` schema is untouched, so
  `UpdatePlanToolTest`'s encoded-schema pin stays as is).
- **Usage indicator**: after each model call a `usage_update` (`used` = prompt tokens,
  `size` = model `context_length`); skipped when either is missing. During an active prompt the
  SDK routes session updates into the prompt event flow, not the `notify` callback.
  The turn-end delivery order is a wire contract (`PromptRunner.endTurn`, the single exit
  ceremony of every terminal branch): the turn's message content first (a synthesized note
  when the text was not streamed live), then the `usage_update`, then the prompt response -
  so the usage indicator never splits a reply. Pinned black-box by `E2eMaxTurnRequestsTest`
  (the interrupted-note branch's event order).

### Tools, file access & output caps

- **File-access exclusions**: the file tools enforce a shared exclusion policy (`tools/FileAccessExclusions.kt`,
  threaded as `ToolContext.fileExclusions`): files whose cwd-relative path matches an exclusion
  glob rule are refused to direct-target tools (`read_file`/`edit_file`/
  `write_file`/`delete_file` hard-error before any I/O - no client
  fs proxy round trip, no permission prompt, uniform incl. new-file writes) through the single
  `resolveToolTarget` seam (`tools/ToolTarget.kt` - the choke point every path-tool
  prologue calls; `move_*` checks source and destination there, `grep`'s single-file branch
  too) and hidden from
  listings/searches (`list_dir` filters entries before the 500-cap, `glob`/`grep` skip them
  in the walk callback; `grep` folds them into the existing skip suffix, while a single-file
  grep `path` is refused like a direct target). The default rule
  set holds three fixed entries: the `.env*.local` secret-file exclusion (bare name, matches
  at any depth) and the two rooted privilege-boundary rules of the `.ai` directory, the
  `.ai/run.json` command file and the `.ai/ddr-acp-agent.json` permission overlay
  - both are files the model must not be able to rewrite, since a granted
  privilege the model could re-apply with an in-project `edit_file` is no boundary at all
  (gitignore-style rooted rules like `secrets/**` match the cwd-relative path). The guard
  also checks the symlink-resolved target name, so an alias link to an excluded file cannot
  smuggle the read (unresolvable paths only skip that extra check - a block, not a gate).
  Refusals name the matched rule (`'<path>' is excluded from tool access (matches exclusion
  rule '<glob>')`) and the system prompt carries a static "Excluded files" section rendered
  from the policy's globs. Rule semantics: globs via the shared `globToRegex`, bare names
  match the basename at any depth. The designed future source is a gitignore-style
  `.aiignore` in the project root feeding `FileAccessExclusions.of()` - matching, error
  text, hiding and the prompt section already consume a rule list, so plumbing it changes
  no tool code. **It is one merged list, not a search-only prune list** (issue #77): a
  matched path is refused to `read_file`/`edit_file`/`write_file`/`delete_file` (and to
  `move_file`/`move_directory` as source and destination) exactly like `.ai/run.json`, so
  naming an excluded path is no escape hatch. Directory patterns consequently need
  gitignore's ancestor semantics - a rule matching a directory covers everything beneath
  it, which the current `Rule.matches` lacks (it compares the exact cwd-relative path) -
  and the search/list roots need validating as well: `list_dir`/`glob`/`grep` only filter
  the children they visit, so an excluded directory named as `path` would still be walked.
  Not excluded on purpose: `bash`/`run` commands (not inspected) and
    `delete_directory` - the delete itself may remove an excluded file (the
  containing-directory residual), but its content never flows: the tree diff evidence (`TreeDiffCollector` in
  `tools/DiffPolicy.kt`) skips exclusion-matched files without
  emitting their bytes (warned to stderr, counted in the `(original content reported for
  M of N files)` note), and the result text carries no file content (`move_file`/`move_directory`
    do check exclusion rules because a move away is a delete of the original path). **Why `.ai/run.json` is excluded**:
    it is not a secret but a privilege
  boundary. Permissions are path-scoped (`permissionDecision` allows every in-project target
    outright), so the permission prompt of the dedicated write tools would be
    cosmetically bypassable - a rejected `create_run_config` can be
    re-applied with an in-project `edit_file`, prompt-free (because `run` is
    non-mutating and prompt-free in every mode, that also re-opens a prompt-free execution
  path for arbitrary command strings). The generic file tools therefore refuse/hide the
  file, and `move_file`/`move_directory` refuse it as source **and** destination in the
  shared `executeMove` (moving it away would delete it just like `delete_file`);
  `list_run_configs`/`create_run_config`/`update_run_config`/`delete_run_config` are
  unaffected because they read and write it through `loadRunConfigRoot`/`writeRunConfigRoot`,
  never through the exclusion-aware tools. Pinned by the `e2e rejected create_run_config
  cannot be re-applied through edit_file on ai run json` scenario (E2eRunConfigCrudTest) and
  the per-tool exclusion tests. **Residual (issue #71)**: a *containing* directory can still
  be moved or deleted (`.ai` itself does not match the rooted rule, its child does) - a
    subtree walk for `move_directory`/`delete_directory` is deliberately not implemented.
- **Tools**: `read/write/edit/move_file/move_directory/delete_file/delete_directory/list/glob/grep`
  (kotlinx-io) + `bash` (killed after
  `tools.bash.timeoutSeconds`, whole process tree) + `web_fetch` (HTTP (S) GET via a
  per-call ktor CIO client, `requestTimeout = 0` + 60s socket idle; manual redirect
  loop (max 5) with per-hop SSRF re-validation - loopback/link-local/site-local/
  any-local IPs, multicast and the IANA special-purpose ranges the InetAddress
  flags miss (CGNAT `100.64/10` incl. Tailscale + the Alibaba metadata service,
  IPv6 ULA `fc00::/7` incl. `fd00:ec2::254`, benchmarking `198.18/15`, `0.0.0.0/8`,
  `192.0.0/24`, `192.88.99/24`, `240/4`, the three TEST-NETs, `100::/64`,
  `100:0:0:1::/64`, `2001::/23` (Teredo, IPv6 benchmarking, ...), `3fff::/20`,
  `5f00::/16`; the IPv6 transition prefixes `64:ff9b::/96`, `64:ff9b:1::/48`
  (NAT64) and `2002::/16` (6to4) are blocked because they embed an IPv4 address
  a relay would translate back to a private target; table `SPECIAL_PURPOSE_NETWORKS`,
  IPv4-mapped literals arrive as `Inet4Address` via the JDK) and `localhost`/`*.local`/
  `.internal` by name refused unless
  `tools.webFetch.allowPrivate: true`, residual validate-then-fetch TOCTOU documented;
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
  filesystem targets -> prompt-free in every mode; kind is the SDK enum value for
  fetch tools) + `update_plan` + `get_current_mode`
  (`tools/GetCurrentModeTool.kt`; returns the turn-captured mode status text — mode,
  semantics and the tools available in it, the same text the modal status messages
  carry — threaded through `ToolContext.modeStatusText` so the model can verify the
  governing mode instead of inferring it; non-mutating, no parameters, every mode,
  prompt-free) + `calc` (`tools/CalcTool.kt`/`tools/CalcExpression.kt`, kind `other`,
  every mode, prompt-free): evaluates a safe arithmetic expression - pure math, no
  variables/assignment/I/O - numbers (decimal, scientific), `+ - * / % ^` with
  standard precedence (`^` right-associative), unary sign, parentheses, the
  functions `sqrt`/`abs`/`min`/`max`/`floor`/`ceil`/`round`/`sin`/`cos`/`tan`/`log`
  (base 10)/`ln`/`exp` and the constants `pi`/`e`. The full language is advertised in
  the tool description so the model knows exactly what it may write. Errors are
  positional (`Unexpected token ')' at position 8`); safety caps: 1000-char
  expressions, 64 nesting levels, division/modulo by zero and non-finite results (overflow, `ln(0)`) refused; `Double`
  results, integrals rendered without `.0`),
  registered in `Main.kt`,
  copied per session; MCP tools are bridged per session (`mcp/McpBridge.kt`) but a name
  collision with a local tool is ignored with a warning - locals can never be shadowed. All
  path-scoped tools resolve relative paths against the session cwd before I/O (the file touched
  is the one the permission check approved); `list_dir`/`glob`/`grep` skip symlinks (kotlinx-io
  follows them by default, which could smuggle reads outside the project). `read/write/edit`
  use the client fs proxy (`tools/FileStore.kt`, unsaved editor state + reviewable diffs) when
  the client advertises read+write fs capabilities and `tools.fsProxy` is not `false`, else a
  local store; `read_file` renders all reads as fixed-width 1-indexed `number│content`
  lines (the content - including its leading indentation - is verbatim after the `│`, so
  the model can read indentation directly off the line rather than inferring it from a
  whitespace-only prefix), byte-identical except for the stripped trailing newline
  terminator, plus a `(Showing lines X-Y of N. Use line=Z and limit to continue.)` footer when
  the window does not cover the whole file, so a truncated read is
  unambiguous and the model can page forward (line numbers are display-only - `edit_file`
  matches raw content, so the model must strip the `number│` prefix; through the client fs
  proxy the footer total is `?` unless the window was resolved via the whole-file fallback,
  which knows the real line count). Line counting follows the shared visual-line convention (`visualLines` in
  `tools/VisualLines.kt`): a trailing newline is a line *terminator*, `""` is
  zero lines, a blank line between terminators is real — an empty file/window renders the
  explicit `(empty content)` instead of a phantom `   1│`. That numbering, the CRLF strip,
  the per-line clamp and the continue footer are one implementation (`renderNumberedWindow` in
  `tools/VisualLines.kt`) shared by `read_file` and
  `web_fetch` (which passes its own `startLine`/`maxLines` labels), so the two renderings
  cannot diverge; `MAX_WINDOW_LINES` is the shared 2000-line paging cap and
  `lineWindowStartError` the shared start-line bounds guard. `grep` match
  numbers (both search paths) and `web_fetch`'s non-HTML pass-through share the convention so
  all line numbers agree with `read_file`; `LocalFileStore` therefore keeps each line's own
  terminator in the window text (the `ClientFileStore` passthrough shape), making a single
  empty line `"\n"`, not `""`. **Client fs proxy windowing** (`ClientFileStore.readFile`):
  the ACP spec does not define whether a `fs/read_text_file` window keeps the last
  line's terminator (and the TS reference client ignores `line`/`limit` and returns the whole
  file), so completeness is never inferred from the response shape: a response with more
  lines than `limit` means a limit-ignoring client and is resolved by the whole-file fallback (`wholeFileWindow`: full
  content re-read, the window sliced locally with `rawVisualLines`
  (per-line terminators), `complete`/`total` computed from the real line count); every
  ambiguous window (exact-`limit` lines, or `line > 1`) is settled by one EOF probe
  (`fsReadTextFile(line = <line after window>, limit = 1)`): empty -> complete, >= 2 lines or
  the whole file again -> whole-file fallback, exactly 1 line -> truncated. A failing probe
  falls back to `complete = false` (a phantom footer beats a silently missing one) and
  cancellation still propagates. Tool arguments are decoded strictly (`JsonObject.stringArg`/`longArg` in
  `tools/Tool.kt`): an explicit JSON `null` is rejected with a dedicated
  `'x' must not be null` error (never coerced into the literal string "null", which would be
  written into files or run as a shell command), an absent key stays
  `Missing 'x'`. Tool-call diffs describe the **whole file** (a fragment-only diff is what
  clients like Zed misrender). The emission rule is the shared policy in
  `tools/DiffPolicy.kt`: diffs are suppressed **only** for changes the client
  fs proxy renders itself (`write_file`/`edit_file` written through `ClientFileStore` -
  avoids a stale duplicate and the pre-read round-trip); every local-only mutation (`delete_file`, `delete_directory`,
  `move_file`, `move_directory` - ACP has no fs
  move/delete, so the proxy renders nothing for them) **always** carries its diffs, which
  are then the client's only rollback baseline. listing/search always use the local disk (ACP
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
  Every moved file is reported as a delete+add diff pair (the v1 encoding of a rename:
  source removal `newText = ""`, destination addition `oldText = null`) and every deleted
  file of a `delete_directory` as a removal diff, collected by `TreeDiffCollector`
  (`tools/DiffPolicy.kt`) in sorted path order within the bounded tree budget (see Output
  caps) and read through the store - the user's view - like the single-file pre-reads;
  exclusion-matched files (`.env*.local`, `.ai/run.json`) are skipped without emitting
  content (the exclusion boundary: their bytes never flow into tool results), and binary (NUL) or unreadable files are
  skipped too - all skips are warned and counted; the
  result text states the rename fact (`(content unchanged)`) and, when the evidence is
  partial, `(original content reported for M of N files)`. `.git` entries are not evidence
  either: the tree-diff walker skips them with the search walker's name-based
  policy, because VCS metadata is not rollback material and it sorts before the source files,
  which would let it consume the bounded budget. That skip is **evidence only** - the
  deletion's symlink scan and the recursive delete/move still cover `.git`, so a symlink
  hidden inside it is still refused.
  The search walker (`walk` in `tools/DirectoryWalk.kt`) always skips `.git` directories (packed
  object files are binary noise for content searches) and symlinks. The asymmetry is
  deliberate: a **named** search root may resolve through a symlink (the permission layer
  resolves it and decides whether the real location is approved - pinned by
  `PermissionAndFileStoreTest`), while links **discovered inside** the walk are never
  followed; a symlinked single-file `grep` path is refused outright instead.
- **Search/list path validation** (`searchRootError` in `tools/DirectoryWalk.kt`): the three
  directory-consuming tools fail loudly on a wrong root instead of silently returning an
  empty result (`No matches`/empty listing) — `Path not found` (missing), `Not a directory`
  (file as root), `Not readable` (chmod-000 root). The parameter is `path` (not `root`:
  a directory is not a root, and `list_dir`/`read_file` already use `path`).
  Out-of-scope residual (documented here so it stays visible): an unlistable subdirectory
  mid-walk still silently prunes its whole subtree (`File.list()` returns null on I/O
  error - the walk-time swallow is deliberate for unreadable dirs, only the root is
  validated), so the skip-note counter undercounts. `grep` additionally accepts a single
  file as `path` (ripgrep model, see Tools), `glob` keeps the file refusal.
- **Output caps**: tool results are bounded so a misbehaving command or huge file cannot
  explode the context. `bash`/`run` keep the last 30k chars of stdout and stderr each,
  prepending `...(truncated: N chars omitted from the beginning)...` (Locale.ROOT; bounded
  memory while reading, UTF-8 chunk-safe) - the output is captured **progressively** into a
  synchronized tail buffer (`ProcessRunner.StreamTail`), so when a backgrounded child holds
  the pipe fds open and the drain grace expires, the tree is force-killed, re-drained once,
  and the output captured so far is snapshotted, never discarded (a fake `(no output, exit N)`
  would misreport such a command); a reader I/O error is recorded into the captured
  output. `read_file` requires `limit` (1..2000 lines; anything else is
  rejected before I/O), refuses non-regular files (`not a regular file: <path>` - a FIFO/device would otherwise
  block on `open(2)` or stream forever),
  truncates lines over **2000 chars** with `... [truncated]` (clamped in
  `renderNumberedWindow` at the render boundary, so the client fs-proxy path clamps
  identically), and errors with
  `line N is past the end of the file (M lines)` when `line` is beyond EOF. There is **no
  whole-file size cap on `read_file`**: the local store streams via the
  byte-level `StreamingLineReader` and early-exits once the window
  is complete, so a windowed read of a huge file is cheap; the footer's exact `total` and
  the past-EOF count come from a newline byte scan (`countVisualLines`, no decoding).
  Per-line accumulation is capped at **64 KB** (`MAX_LINE_ACCUMULATION_BYTES`: the kept
  prefix is stored, the tail is skip-scanned to the next `\n` and the line is marked
  truncated) - the no-newline-file (minified bundle) memory bound, and the cap that keeps
  the file caps off `read_file`/`grep`; the kept prefix is always >= 16384 chars, so the
  2000/500-char display caps always mark a clamped line visibly (never silent truncation).
  `read_file`'s `line` and
  `web_fetch`'s `startLine` are bounded above at `Int.MAX_VALUE`: a larger value
  would overflow at the `toInt()` narrowing to a negative start line,
  defeating the window/past-EOF guards, so it is refused with the
  clean `'line'`/`'startLine'` validation message
  `'<arg>' must be at most 2147483647` before any I/O.
  `list_dir`/`glob` list at most
  500 entries with a `...(N more entries omitted)` suffix; `grep` caps matches at 500 **while collecting** (only the 500
  smallest by path/line are kept, so a broad pattern
  over a large tree cannot grow the tool's memory with the repository) **and stops
  reading once that prefix is settled** (`BoundedMatches.admits`: a file whose relative
  path sorts after the largest kept match is not read or scanned - the saving is
  walk-order dependent, the kept set is not; a late-sorting binary file is
  therefore cap-pruned instead of counted in the skip note),
  truncates each matched line at 500 chars (`...` suffix), and skips binary files (NUL sniff
  over the whole file via `StreamingLineReader.sawNul` - the per-file matches are staged and
  discarded on the binary verdict) and excluded files (see File-access exclusions),
  reporting skips as
  `...(N binary or excluded files skipped)`. `grep` streams its lines (no whole-file
  `split`) and skips no file for its size; matching runs on each
  line's kept 64 KB prefix, so a match past the accumulation cap *inside one line* is the
  documented trade-off (pinned by `GrepToolTest`).
  `FileStore.readRaw` (exact bytes, size-capped, no per-line truncation) is the store
  operation for `edit_file` matching and the diff pre-reads - display reads (`readFile`) may
  be truncated, raw reads must not be. Its exception types are part of the contract (`FileAbsentException` = no such
  file, the caller's "new file" signal;
  `FileTooLargeException`; plain `FileStoreException` = exists but unreadable), because a
  diff's `oldText = null` means "new file" in ACP. `LocalFileStore` opens **only regular
  files** (`metadata.isRegularFile`): a FIFO/device reports size -1, passes the size cap and
  then blocks on `open(2)` or streams without end. **Diff payloads are capped** so a
  tree mutation cannot blow up the wire: 100k chars per side (`MAX_DIFF_CONTENT_LENGTH`),
  at most 50 files per call (`MAX_DIFF_FILES`) and 1 MiB of aggregate diff payload per
  call (`MAX_DIFF_TOTAL_LENGTH`, the caps compose) - the reported prefix is deterministic (sorted path order,
  `TreeDiffCollector` in `tools/DiffPolicy.kt`), skips are counted in
  the `(original content reported for M of N files)` note and every dropped diff is
  visible in the result text and on stderr. MCP tool results are intentionally uncapped.

### Permissions

- **Permissions (path-aware, tiered)**: path-scoped tools inside the session cwd run without asking;
  anything outside - reads and writes alike - and any mutating non-path tool (`bash`) ask via
  `session/request_permission` (unless a tier covers it; the tiers themselves are under
  Configuration / Directory permission tiers). The non-mutating path tools (`read_file`/`list_dir`/`glob`/`grep`) run
  prompt-free when all targets resolve inside the
  readable tier (`isWithinAnyRoot` in `tools/Containment.kt`, resolved
  against the session cwd so a symlink escape still gates); writes/moves/deletes under it
  always prompt. **The agent's own scratch directory is
  read-trusted like a readable directory**
  (`Config.scratchDir`, config key `scratchDir`, default `/tmp/ddr-acp-agent`):
  `AgentSessionImpl` merges `config.readableDirectories + scratchDir` into one
  `permissionPolicy.readableDirectories` - the single merge point, consumed by
  `permissionDecision` and the
  system prompt - so the config surface keeps the two apart (the scratch dir is never part of
  `readableDirectories`). It is prepared by `prepareScratchDirectory` (`tools/ScratchDirectory.kt`)
  at session assembly: created `0700`, and an existing path is verified rather than assumed
  because `isWithinAnyRoot` resolves the root *through* symlinks - a symlink, a
  non-directory or a foreign-owned directory fails session creation, a directory we own with
  a wider mode is repaired to `0700`. The owner check compares `UserPrincipal`s (a probe file
  in the temp dir is created and its owner read back), never `user.name`, which resolves to
  `?` in some containers. The directory is never cleaned up; writes there still prompt, so
  plan mode stays read-only. Preparation requires a POSIX filesystem (mode + ownership are
  POSIX concepts) and resolves the directory's real path, so a symlinked *parent* cannot
  smuggle the trust root elsewhere. Pinned by `ScratchDirectoryTest`, `ConfigTest` (default,
  profile selection, the inert `DDR_ACP_AGENT_*` family, absolute/non-root validation,
  normalization, "not part of readableDirectories", sandbox keys cannot fault the agent),
  `PermissionAndFileStoreTest` (read/grep prompt-free, write still prompts, siblings not
  covered) and `E2eFileStoreTest` (plan-mode grep prompt-free, path surfaced in the prompt,
  a symlinked scratch directory refusing `session/new`).
  The paths are also surfaced as a static "Trusted paths" system-prompt
  section (`SystemPromptBuilder`, a `## Trusted paths` heading with a read tier and a write
  tier, plus the deny note when `unlistedPaths = deny`)
  so the model knows where it operates prompt-free. MCP tools
  always prompt **unless** the server annotates the
  tool `readOnlyHint: true` and annotations are trusted (`mcp.trustAnnotations`, default
  enabled; annotations are untrusted hints per the MCP spec, so absent/unset hints keep the
  pessimistic always-prompt default). The trusted `title` annotation replaces the bare tool
  name in permission prompts and tool-call progress (MCP names are often machine-prefixed);
  the display kind derives from `readOnlyHint`/`destructiveHint` (read-only `other`,
  non-destructive `edit`, potentially destructive `delete`) and is cosmetic only.
  A tool's targets come from `AgentTool.targetPaths(arguments)` (`tools/Tool.kt`, defaults to
  the single `targetPath`; `move_file`/`move_directory` override it with source + destination)
  and every target must lie inside the cwd for a prompt-free call - a move with an
  out-of-project destination asks even when the source is inside.
  In-project writes are further gated by mode (plan is read-only). The containment check (`tools/Containment.kt`) is
  symlink-safe and resolves relative paths against the session cwd.
  `allow_always`/`reject_always` persist per session (keyed by tool name); `allow_once`/
  `reject_once` apply once. The decision follows the *kind* of the offered option, never the id
  spelling: an id we did not offer is a denial (warned on stderr, never guessed into an allow).
  The permanent branches are pinned by
  `ToolCallExecutorTest` (allow_always/reject_always short-circuit + the grants,
  allow_once contrast, an allow-shaped unknown id denying) and `E2eFileStoreTest` (two prompts, one session grant);
  the grants are **session-memory only** (not in `SessionRecord`, evaporate on
  restart) - deliberately pinned so the over-grant redesign (issue #3, per-path
  keys) must change these pins explicitly rather than silently regress the
  contract. `SessionState` owns them behind `grantAlways`/`rejectAlways`/
  `permissionDecisionFor` and exposes a read-only view, so
  `ToolCallExecutor` never mutates the map directly. **Tool-call titles**: the JetBrains ACP client
  renders only the `title` of a tool call in permission prompts and progress and ignores the
  `rawInput` field that carries the arguments, so a bare `run`/`bash` title would leave the user
  confirming blind (a permission dialog can show the bare
  `command` argument instead of the `bash(command: ...)` title); keep titles argument-bearing
  anyway (other clients render them) and make commands self-describing, and re-verify against a
  concrete client rather than assuming. Tools with meaningful arguments therefore override
  `AgentTool.title(arguments)` (hook in `tools/Tool.kt`, default `null` = bare tool name):
  `bash`, `write_file` (path + content size), `web_fetch`, `calc`, the `run` tool, the
  run-config write tools and the move/delete tools (their prompts are safety-critical: a bare
  title would leave the user confirming a deletion blind). The `run` tool and the run-config
  write tools lead with the config name (`run(echo)`, `run(echo: <args>)`,
  `create_run_config(echo: <command>)`, `delete_run_config(echo)`) so client-side title elision
  cannot hide which configuration is executed/created - `formatRunToolTitle` /
  `formatRunConfigToolTitle` in `tools/Tool.kt` read named keys, so titles are order-independent
  regardless of the JSON key order the model chose. The remaining overriding tools use
  `formatToolTitle(name, args)` - a `name(key: value, ...)` summary, gemini-cli style - and every
  formatter caps each value part at 100 chars (ellipsized per value, so a long value cannot push
  the identifying name out of the title) and flattens embedded newlines so permission prompts
  stay single-line. `rawInput` is still sent unchanged for clients that do render it (Zed). The
  path tools that only prompt for out-of-project access (`read_file`/`edit_file`/`list_dir`/
  `glob`/`grep`) keep the bare tool name. **Tool-call results**: completed/failed
  `tool_call_update`s carry the result text as `content` blocks (and the error text on
  permission denial), so clients that ignore `rawOutput` still render the outcome;
  edit-kind tools additionally emit `ToolCallContent.Diff` (spec v1 `diff` blocks) so
  file changes are visible without the client fs proxy - every diff describes the **whole file**: `edit_file` emits
  the full old/new content (a fragment-only diff is what
  Zed-style clients misrender), `write_file` best-effort pre-reads the old
  content (`oldText = null` **only** for a genuinely absent file - `FileAbsentException`, so an
  existing-but-unreadable file never claims to be new), `delete_file`/`delete_directory`
  carry the removed content as `newText = ""` and every moved file is reported as a
  delete+add pair (`move_file`/`move_directory`). Diff blocks are suppressed **only** for changes the client
  fs proxy renders itself (write/edit through the proxy -
  avoids a stale duplicate and the pre-read round-trip); local-only mutations always carry
  theirs, since the proxy renders nothing for them (the shared rule in
  `tools/DiffPolicy.kt`). **A dropped diff is never silent**: every
  skip (size cap, pre-read failure, binary tree file) appends a fact-based
  `(diff omitted: <reason>)` note to the result text and logs a `warn` with path and
  reason, and tree mutations state `(original content reported for M of N files)` when
  their evidence is partial; happy-path result texts are unchanged. Path-scoped tool calls carry `locations`
  (`tool_call`, `request_permission` and load replay) for the client's follow-along
  surface. `rawOutput` keeps the plain result for wire compat. The spec's optional
  programmatic `tool_call.name` is **not** emitted: no `acp` SDK release up to 0.34.0 carries
  it (`SessionUpdate.ToolCall` still has no `name` property) - tracked by issue #64. **Revert
  path**: when JetBrains renders
  `rawInput` (or ACP v2 permission `subject`), drop the one-line `title` overrides and the
  call sites (`tool_call` notification, `request_permission`, completed/denied updates,
  load replay in `AgentSessionImpl.kt`) fall back to `tool.name` without further changes.

### MCP consumption

- **MCP consumption**: servers come exclusively from the client's `session/new` `mcpServers`;
  all three transports work on JVM (see Recipes); `initialize` advertises
  `mcpCapabilities.http/sse`. **All three transports are covered end-to-end**
  (`E2eMcpToolPermissionTest` over `MockMcpServer` streamable HTTP,
  `E2eMcpStdioTransportTest` over `StdioMcpMockServer`, `E2eMcpSseTransportTest` over
  `MockMcpSseServer`), all serving the same tool payloads from `MockMcpTools` so a
  scenario says the same thing about the agent over any transport; the SSE scenario also
  pins header forwarding (`McpServer.Sse.headers`). The streamable-HTTP client (`McpConnector.connectHttp`)
  installs the ktor SSE plugin so the transport's optional GET SSE probe degrades cleanly on
  a 405 ("stream disabled") instead of throwing - JSON-only servers (no GET stream) work.
  The **classic SSE** transport is deprecated in the MCP SDK since 0.16.0 (SEP-2596, in favour
  of streamable HTTP); `McpConnector.connectSse` keeps it - ACP's `mcpServers` still carries an
  `sse` variant a client may hand us - with a local `@Suppress("DEPRECATION")` and the reason
  documented in place, so it can be dropped when ACP drops the variant.
  MCP tool behavior annotations (`Tool.annotations`) drive the permission decision when
  trusted (`mcp.trustAnnotations`, default enabled; see Permissions) - pinned black-box by
  `E2eMcpToolPermissionTest` with the in-process `MockMcpServer` (streamable-HTTP JSON-only
  mock: JSON POST responses, 202 for `notifications/initialized`, 405 for GET/DELETE;
  tools carry the mandatory `inputSchema`) and over stdio by `E2eMcpStdioTransportTest`
  (read-only prompt-free, unannotated prompts + executes; the marker file is written by the *child process*, so it
  proves `tools/call` crossed the pipe) and over classic SSE by `E2eMcpSseTransportTest`
  (`MockMcpSseServer`: `GET /sse` streams the `endpoint` event then the JSON-RPC
  responses, `POST /messages` answers 202 - both required by `SseClientTransport`). The
  transport-specific scenarios pin one thing beyond the shared permission behavior each:
  `McpServer.Stdio.env` reaching the child environment (stdio) and
  `McpServer.Sse.headers` reaching the server on the SSE request (SSE). **A stdio
  child is started like every other session-scoped child**:
  its working directory is the session `cwd` (`ProcessBuilder.directory`, the same
  workspace `bash`/`run` get - never the directory the IDE happened to spawn the agent in,
  which only coincides in the single-project case), and its environment is **built, not
  inherited**: `mcpChildEnvironment` (`mcp/McpConnector.kt`) keeps an allowlist of process
  basics (`PATH`, `HOME`, `TMPDIR`/`TMP`/`TEMP`, `LANG`/`LC_ALL`/`LC_CTYPE`, `TERM`, `TZ`,
  `USER`, `LOGNAME`) and applies the client's `McpServer.Stdio.env` entries last, so a
  server declaration can set anything (that per-server `env` is the deliberate escape
  hatch - there is no process-wide passthrough knob, which would be a second
  allowlist to keep in sync and a session-wide grant). Everything else the agent env
  carries - secrets, `OPENROUTER_*`, `ACP_*`, `DDR_ACP_*`, IDE internals - stays out: a
  server is spawned automatically at `session/new`, before any permission decision, so an
  inherited environment is an unreviewed grant. `bash`/`run` deliberately keep the inherited
  env (they are permission-gated per command and are the user's own shell). Pinned by
  `McpConnectorTest` (allowlist, secrets excluded, client entries added and winning) and
  black-box by the `E2eMcpStdioTransportTest` launch scenario, which starts the *agent*
  from a different directory than the session `cwd` and reads the child's reported cwd +
  key presence back out of the marker file (without the different agent directory the pin
  would hold trivially).
  The advertised tool schema is the server's `inputSchema` serialized **verbatim**
  via the SDK's own `McpJson` (`mcp/McpBridge.kt`), so `required`, `$defs` and
  `$schema` round-trip losslessly and the LLM knows which MCP arguments are
  mandatory (a `type`+`properties` rebuild would drop all three; the
  `type = "object"` pin is the SDK's own `@EncodeDefault`, not a loss). Pinned at
  the unit level (`McpBridgeTest`) and at the wire level (e2e: the schema
  reaches the chat request's `function.parameters`).

### LLM streaming & auto provider routing

- **LLM streaming** (Responses API dialect): a hand-rolled SSE line scan (`data:` events, `[DONE]` terminator) that
  relays **raw event objects**;
  the runner switches on `type` and skips unknown event types, so a new upstream event cannot
  fail a stream. `response.reasoning*.delta` (and `response.reasoning_text.delta`, the name
  the live endpoint uses) relay as `agent_thought_chunk`, `response.content_part.delta` /
  `response.output_text.delta` as `agent_message_chunk`; deltas are relayed only when
  non-empty, so a provider that interleaves empty content with reasoning emits no blank
  chunk. `response.output_item.added`/`.done` accumulate the iteration's items -
  `reasoning`, `message` and `function_call` - by `output_index`;
  `response.function_call_arguments.delta` merges argument fragments.
  Terminal events are `response.completed` / `response.incomplete` (`response.failed` and a
  top-level `{"error":…}` raise `LlmException` in the transport). A stream that ends without
  a terminal event raises `LlmException`, so a dead stream never executes partial tool calls
  or ends with an empty END_TURN. Classification happens on the response object:
  `incomplete_details.reason == "max_output_tokens"` (or `status == "incomplete"`, or an
  iteration with no text **and** no tool calls) triggers exactly one continuation pass - the
  partial text (if any) is kept, ALL tool calls (including complete ones) are dropped and
  never executed (the model re-issues them after the continuation), a synthetic user
  "continue" prompt is appended, and the retry runs even when the tool-iteration budget is
  exhausted. A second truncation/empty completion ends the turn with an honest "response
  interrupted" note (+ `end_turn`, no exception). `incomplete_details.reason ==
  "content_filter"` ends immediately with a "blocked by content filtering" note (never
  retried) - it is **not** an `error.code`, which carries transport categories
  (`invalid_prompt`, `server_error`, ...) only. An undocumented status (outside the API's
  set) is classified by usable output: with text or complete tool calls it is a
  natural completion (the tool calls execute, no continuation retry), with empty output it
  retries conservatively as empty. Both abnormal cases log to stderr (`warn` on first,
  `error` when still broken) so the problem stays visible. Replay renders the synthetic
  "continue" user message as a normal bubble (accepted).
  Every `agent_message_chunk`/`agent_thought_chunk` of one LLM iteration carries the same
  UUIDv7 `messageId` (time-ordered, `com.github.f4b6a3:uuid-creator`; fresh per iteration) so
  clients group the iteration's reasoning and reply into a single message; pinned e2e at
  the decoded-object and raw-wire level. **Pitfall**: the `messageId` RFD's "unique per message" is still violated by
  sharing one
  id across the distinct `agent_message` and `agent_thought` streams of the same iteration -
  deliberate v1 choice so reasoning folds into the message block it belongs to. v2 makes
  `messageId` mandatory and keys whole `agent_message`/`agent_thought` messages by it, so
  this **must change** on v2 (distinct ids per message). Re-verify against v2 when the agent
  runtime is upgraded - the v1 equivalence is pinned **v1-only** by the labelled assertion in
  `E2eWireConformanceTest` (the marker for the mandatory v2 flip).
  Requests carry app attribution headers (`HTTP-Referer` + `X-OpenRouter-Title`).
- **Auto provider routing**: by default (`openRouter.autoThroughputSorting: false`
  disables) every chat request carries `provider: {sort: "throughput", max_price.completion =
  median endpoint completion price}` (USD per million tokens,
  `providerrouting/ProviderRouting.kt`, lazy per-model cache over the shared endpoints
  feed that also backs the manual `provider` config option - see the provider option under
  Model + reasoning + provider options); fail-open: fetch/parse failure
  or empty feed omits the `provider` field (and hides the option); the disabled auto policy never fetches for
  itself, while the provider option reads the feed regardless (the picker stays visible). Unparseable completion
  prices are excluded from the median instead of failing the feed (all-unparseable fails open - never a $0 cap);
  such endpoints stay selectable as manual picks. `ProviderPreferences` is all-optional (`sort`/`order`/`max_price`), so
  the auto policy sends sort+cap and a manual pick sends
  `order` only; `llmWireJson`'s `explicitNulls=false` keeps absent members off the wire.

### Prompt & cancellation

- **Prompt capabilities**: advertises `image` + `embeddedContext` (no `audio`). Image blocks
  become base64 data URIs (mime default `image/png`) only when the model's
  `architecture.input_modalities` lists image, else a text placeholder; text `resource` blocks
  are inlined as `Resource <uri>:\n<text>`, blob/audio degrade to placeholders,
  `resource_link` renders as a markdown link (`contentBlocksToInputParts` in
  `agent/PromptContent.kt`).
- **Project instructions**: `<cwd>/AGENTS.md` is read from disk every turn and injected as
  `## Project Instructions (from AGENTS.md)` (re-read per turn, so mid-session edits apply);
  missing file ignored, read errors logged and never fail the turn (`agent/AgentsMd.kt`).
- **Slash command `/info`**: advertised right after every session setup by
  `AgentSession.postInitialize` (the SDK hook for "additional notifications like Commands") as
  one `available_commands_update` carrying the single command `info` (`infoAvailableCommand()`
  in `agent/InfoCommand.kt` - the same file holds the detector, so the advertised name and the
  matched name cannot drift apart); the notification is a
  complete replacement list, so re-sending it on load/resume stays correct. The invocation is a
  regular `session/prompt` whose **single** text block trims to `/info` or starts with `/info`
  followed by whitespace - a word boundary keeps `/infofoo` an ordinary prompt, as do prose
  mentioning it, any second content block and a different case. The reply is one
  `agent_message_chunk` (Markdown: agent version + build, session id/cwd/mode with the mode's
  advertised description, default + session model, the reasoning/provider values the config
  surface states, the behaviour switches, and the trusted paths incl. the API key *file* - never
  the key - and the session's *prepared* scratch directory, which is the path the trust checks
  actually use) followed by `END_TURN`. **The JetBrains ACP client lists and autocompletes
  the advertised command**, so the advertisement is
  the discovery path; a manually typed `/info` reaches the same interception, so a client that
  ignores the notification still works (re-verify client-side rendering by hand, the e2e harness
  cannot pin it - see Manual validation). It is answered **before any state is touched**: no history
  entry, no record, no LLM call, no tokens - a diagnostic must not become context for later
  turns, so a `/info`-only session stays untitled and the dump is never stored; a record
  written at close/shutdown (standard for any open session) carries an empty history.
  Text after the command word is
  discarded and the reply leads with a note naming it (`**Note:** … discarded: "<text>"`,
  flattened and capped at 200 chars), so a user's words are never dropped silently.
  `InfoSnapshot`/`renderInfo` (`agent/InfoCommand.kt`) are pure; the session only fills the
  snapshot with its live selections (`SessionConfigOptions.reasoningStatus/providerStatus`).
  `Config.openRouterApiKeyFile` (recorded by `Config.load`, path only) backs the key-file line.
  No staleness note by design: the README states that the config is resolved once at startup.
  Pinned by `InfoCommandTest` (detection, rendering, no key value), `AgentSessionInfoTest`
  (one chunk + `END_TURN`, no record, `-32002` on a closed session) and `E2eInfoCommandTest`
  (advertisement, zero model calls, discarded note, `/infofoo` reaching the model, `/info`
  absent from the next request's history).
- **`$/cancel_request`**: handled natively by the SDK in both directions; a cancelled
  `session/request_permission` dismisses the client's prompt, and `shouldAllow` rethrows
  `CancellationException` instead of swallowing it into a bogus "Permission denied". A
  cancelled turn also terminates a running bash command (interruptible wait +
  process-tree kill), so `session/cancel` does not leave orphans behind.
  Cancellation must **propagate out of tool execution**; it is centralized in
  `executeSafely` (`tools/Tool.kt`): every tool body runs through this wrapper (`runShellCommand` routes the `bash`/
  `run` tools through it, `ToolCallExecutor`
  wraps `tool.execute` with it), which rethrows `CancellationException` before the
  generic catch. A swallowing tool turns `session/cancel` into a bogus failed
  `ToolResult`; the wrapper makes that structurally impossible for the tool bodies (the remaining manual rethrows are
  the inner best-effort pre-reads:
  `writeResultDiff` in `WriteFileTool.kt` and `deleteResultDiff` in `DeleteFileTool.kt` - both also keep their
  non-cancellation catch, so a failing pre-read drops only the diff and the write/delete still proceeds). Same
  contract for the fs-proxy file tools (`ReadFileTool`/`EditFileTool`/`WriteFileTool` - with
  the client fs proxy their
  `FileStore` calls are suspending RPCs; note only `delete_file`'s *pre-read* goes through
  the `FileStore`, the delete itself is local-disk-only). Testing this swallow needs an outcome-capture
  detector, not `Deferred.isCancelled` — a `cancelAndJoin`ed deferred is
  `isCancelled` even when the body swallowed and returned a value (`BashToolTest`/`RunToolTest` "cancelling the tool
  during a running command"
  and `FileToolCancelTest` "executeSafely rethrows cancellation"
  tests; each fails with the swallow re-introduced). The SDK's
  `SafeCollector.emit` (`currentContext.ensureActive()`) masks a swallowed
  cancellation before the bogus `ToolCallUpdate(FAILED)` is emitted, so the
  end-to-end symptom is latent, not observed.
  A cancelled turn is **persisted**: `prompt`'s `finally` writes the record under
  `NonCancellable` when the turn did not complete, so the user message, the streamed partial
  reply and every applied tool call survive the cancel (the dangling call is closed
  by the pairing repair).

### Docker sandbox

- **Docker sandbox**: CI-built (`build-image.yml`) image on
  `ghcr.io/dontdrinkandroot/acp-agent:latest` (the workflow's prune step follows the name in
  both API paths); multi-stage
  Dockerfile (temurin-25 builder with BuildKit cache mount, installDist perms normalized ->
  toolchain base `dev`). The builder stage copies an explicit build-input allowlist (wrapper
  scripts, wrapper jars, `gradle/libs.versions.toml`, the build scripts, `gradle.properties`,
  `src/`), so a new file the Gradle build reads must be added to that COPY list;
  `DockerfileBuildInputsTest` pins the allowlist against the real build inputs and the
  `ENTRYPOINT` path. The image ships both entry points under `/opt/acp-agent` (one `lib/`).
- **Entry points and image provisioning**: `bin/acp-agent` (direct) and
  `bin/acp-agent-sandbox` (sandbox). The sandbox wrapper builds `installDist`, runs
  `docker image inspect` and builds the image via `build-docker` when it is missing or when
  `--rebuild` is given - **it never pulls**: updating the upstream image is a manual
  `docker pull`, after which the inspect succeeds and the build is skipped (README states
  "profiles need `--rebuild` once" next to it). The
  wrapper is deliberately untested (its remaining logic is a handful of lines and a bug
  surfaces at first launch; tests would nest `installDist`).
- **The supervisor** (`sandbox/SandboxMain.kt`, the `acp-agent-sandbox` entry point):
  resolve -> compose -> spawn `docker run`; explicit pumps (child stdout to our stdout, child
  stderr to our stderr - never merged), stdin pumped until EOF and then **half-closed**
  (never kills the container, so the agent's shutdown flush can finish), a shutdown hook
  forwards SIGTERM/SIGINT with a bounded wait, `--rm` reaps, the mask temp file is removed,
  the child's exit code is propagated. It **never parses ACP** and every byte it produces
  goes to stderr. Docker is resolved from `PATH`; an unresolvable CLI exits 127 with a named
  message instead of a raw `IOException`. Strict arguments: an unknown argument is not
  special-cased (exit 2). `--profile <name>` is parsed by both sides (the supervisor needs the
  same file to compose the mounts) and forwarded verbatim into the agent argv. **The launch cwd
  is assumed to equal the session cwd** (nothing enforces it): the project bind and
  `--workdir` are composed from it, and the later per-project config depends on it too.
- **Composition is a pure function**: `resolveSandboxPlan` (`sandbox/SandboxPlanResolution.kt`,
  the only place with host I/O: probes/creation via `HostProbes`) produces typed mounts, tmpfs
  specs, env entries and typed warnings; `composeDockerInvocation`
  (`sandbox/DockerInvocation.kt`) renders the argv - no string concatenation, no word
  splitting (`extraArgs`/`capAdd` are typed lists). Pinned by the 49-row table (see Testing),
  rows against fixture facts only.
- **Hardening**: `--cap-drop=ALL` + `no-new-privileges`,
  read-only rootfs (`sandbox.rwRootfs: true` relaxes), tmpfs `/tmp`, `--user host-uid:gid`
  (the *real* ids via `UnixSystem`; `id -u`/`id -g` coincide for a normally launched JVM and
  are pinned once by the process test). **`--network host` by default**
  (`sandbox.network` restores isolation) with `--add-host=host.docker.internal:127.0.0.1` in
  host mode / `:host-gateway` on an explicit network (plain Linux daemons do not resolve
  `host.docker.internal`; the mapping lets the container reach MCP servers running on the
  host, e.g. the PhpStorm MCP server at `http://host.docker.internal:<port>/sse`). Host
  networking is what makes Gradle's cross-process cache locking work across the host and
  container daemons (loopback UDP lock handover, see the Gradle lock timeout pitfall) and what
  lets the container reach host services bound to loopback. The trade-off: no network
  isolation, and Docker's embedded DNS is unavailable (compose service names like `postgres`
  only resolve on the compose network - with host networking a project's own test config must
  target `127.0.0.1:<published-port>` instead, e.g. via a `.env.test.local` the project
  gitignores).
- **Home layout**: tmpfs home (`sandbox.homeVolume` -> named volume) plus uid-mapped tmpfs
  mounts for `~/.local`, `~/.local/share`, `~/.local/state` and (when a config dir is mounted)
  `~/.config`: the image has no `~/.local` and a home tmpfs/named volume would shadow it
  anyway, so runc would create the deep bind-mount mountpoints (session state at
  `~/.local/state/ddr-acp-agent`, pnpm store) root-owned inside the container and the agent
  uid could not write anything else under it. The state/store/config binds land on top of
  these tmpfs mounts (moby composes all mounts shallowest-first into the OCI spec and runc
  mounts in that order, so CLI argv order is irrelevant - the composer still emits them in
  argv order).
- **Host tool caches** (`sandbox.mountCaches: false` disables the whole cache composition - no
  mounts, no cache env, not even validation). Two cases: an explicitly configured
  cache (the tool's env var set, e.g. `UV_CACHE_DIR`) is **identity-bound at the identical
  in-container path with the env value forwarded verbatim** (the tool's own config already
  points there and host-created symlinks into the cache keep resolving); set-but-unusable
  config - relative, `/`, uncreatable - fails loudly before any `docker run`, and the env
  value is forwarded even when the mount is skipped (inside the project dir, deduped dst).
  `UV_LINK_MODE` is forwarded when set. Without an env var, the first existing host default
  (e.g. `$XDG_CACHE_HOME/uv`, never created) is mounted at the tool's in-container default
  with the env var pinned to the mount (`KONAN_DATA_DIR` -> `~/.konan`, `UV_CACHE_DIR` ->
  `~/.cache/uv`, ...) - relocation stays necessary there because the container home differs
  from the host home. **pnpm** is a regular tool cache
  (`PNPM_CONFIG_STORE_DIR`/`PNPM_CONFIG_CACHE_DIR`, pnpm.io/configuring, plus
  `PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE` forwarding); `PNPM_HOME` is deliberately **not**
  forwarded (it points at the host's standalone pnpm install).
- **Gradle is the deliberate exception**: the whole `GRADLE_USER_HOME` is *not* shared (one
  daemon registry + logs across the host and container PID namespaces would let probing
  clients prune each other's daemons and `gradle --stop` kill both sides' daemons). The GUH
  root is a container-private uid-mapped `.gradle` tmpfs (holding `daemon/`, `.tmp`,
  `kotlin/`, `native/`), and everything Gradle documents as a *cache* (`directory_layout`,
  "Shared caches" / "Version-specific caches") is shared rw at its in-container path:
  `caches/modules-2`, `caches/jars-*`, `caches/build-cache-*`, legacy `caches/transforms-*`,
  the version-bound `caches/<ver>/transforms` + `caches/<ver>/generated-gradle-jars`,
  `wrapper/dists` and `jdks` (AGP transform output alone would fill the 1 GB caches
  tmpfs). Everything else stays on the private tmpfs: absolute-path-keyed execution state
  (`journal-1`/`file-access.bin`, `caches/<ver>/fileHashes`, `jvms`), JVM-sensitive compiled
  scripts (`kotlin-dsl`, `scripts`) and `caches/<ver>/cc-keystore` - the configuration-cache
  **encryption key**, which must never leave the container. Each version dir gets its own
  per-version uid tmpfs with the two shared children bound on top; version names come from
  the digit-named `caches/<ver>` dirs **and** the shared wrapper dists
  (`gradle-<version>-<bin|all>`), so a wrapper upgrade is shared from the first session. The
  `caches/` and `wrapper/` intermediate depths get their own per-depth uid tmpfs (runc creates
  missing mountpoint parents root-owned, which surfaces as `Could not open cache directory ...
  caches/<ver>/jvms`). All home-side tmpfs mounts (`/home/dev` and the `.gradle` depths incl.
  the per-version ones) are **exec-allowed** (`exec` appended, overriding docker's `noexec`
  default): toolchains execute native binaries extracted into those caches - AGP's AAPT2, the
  compiled Kotlin DSL scripts, Kotlin/Native under `~/.konan` - which surfaces as
  `AAPT2 ... Daemon startup failed` under noexec. `~/.cache` and `~/.local` keep
  the noexec default. `GRADLE_USER_HOME` is always pinned to the private root, independent of
  `sandbox.mountCaches`; the leaf binds are gated by it. A relative or `/` `GRADLE_USER_HOME`
  is ignored in favour of `$HOME/.gradle` (Gradle's own resolution has no such value; unlike
  the tool caches it does not abort the launch).
- **Android SDK**: explicit `ANDROID_HOME`/`ANDROID_SDK_ROOT` (first one set wins) is
  identity-bound and both variables carry the configured value; a set-but-unusable var -
  relative, `/`, missing dir - fails loudly before any `docker run`; inside the project dir
  the mount is skipped and the variables keep the host path. The default candidate (existing
  `$HOME/Android/Sdk`, never created) keeps the relocation to the in-container default with
  both variables pinned; inside the project dir it is skipped entirely.
- **Session state and config**: host session state is shared rw
  (`sandbox.stateDir` overrides the host-side dir; default `$XDG_STATE_HOME/ddr-acp-agent`,
  resolved by the shared `stateHome` so host and agent agree). The host config directory
  (`~/.config/ddr-acp-agent`, resolved by the agent's own `Config.configDir`, so the mounted and
  the read directory cannot diverge) is mounted **read-only** at the identical in-container path
  when it exists (with a `~/.config` uid tmpfs underneath) - that mount is the agent's only key
  source inside the container, since no key variable exists at all. A state or config directory
  *inside the project directory* is skipped with a typed warning: the project bind only exposes
  the host path, while the agent writes/reads the container's XDG paths, so a silent skip would
  lose the sessions (or leave the agent without its config).
- **Permission lists are the mount list** (one config file, two readers):
  `permissions.readableDirectories` mounts each path `:ro`, `…writableDirectories` mounts it
  rw (a path in both is mounted rw once), at the identical in-container path (src == dst like
  the CWD). The lists are the **merged** ones (`mergePermissionTiers(config.json,
  .ai/ddr-acp-agent.json)`, read through the shared key-free source step
  (`readConfigSources`) - the supervisor mounts exactly what the containerized agent trusts.
  Absolute only, `/` is rejected in the parser, host existence is required only for paths
  actually mounted - paths under the covered roots (project dir/container home/`tmpfs`) are
  skipped with a typed warning regardless of host existence. A configured directory that is
  missing is a warning in a direct run (`Config.directoryWarnings`) but **fatal in sandbox
  mode** (docker cannot bind-mount a missing source) - and already fatal at parse time when
  it comes from the project overlay (a checked-in file names directories of *this*
  checkout). Mounts are emitted before every built-in mount and
  sorted shallowest-first, so built-ins and later, deeper mounts shadow shallower ones
  (mounting a CWD parent ro works; the project dir itself stays rw). `scratchDir` is never
  mounted (a container path; the container's `/tmp` is a private tmpfs, so scratch content is
  per-container-session).
- **Also forwarded**: `TZ` (the container wall clock follows the host zone; the read-only
  rootfs has no `/etc/localtime` and the agent renders local-offset timestamps),
  the host git identity (`GIT_AUTHOR_*`/`GIT_COMMITTER_*` when `git config user.name` and
  `user.email` are both set), `HOME`/`TERM`/`XDG_*` container pins, and host `.env.local`
  masked with an empty read-only file (temp file created by the supervisor, deleted on exit). **No agent-config
  environment is forwarded at all** - `DDR_ACP_AGENT_*` is dead,
  and the `--env` allowlist pin is the negative closure.
- Local builds via `./build-docker`; supervisor input is typed config (no env knobs), read
  from the **same `config.json`** the agent reads (strict parser, no API key required). A
  profile selected with `--profile` must exist on both sides. Supervisor output is stderr-only
  - ACP travels over the container stdin/stdout.

## Testing

Drive the suites through the run configurations: `test` for the whole suite,
`test_class` (single class/method via `[args]`; re-links installDist) for one
target, `show_failures` for the failure messages of the last run.

The suite is unit tests plus a **black-box e2e harness** (feature-area scenario
classes in `net/dontdrinkandroot/acpagent/e2e` sharing the `E2eAgentTest` base).
The e2e scenarios run the linked `installDist` agent as a separate OS process
with the **real ACP SDK client** and a local mock OpenRouter server, and pin the
agent's **wire contract**:

The harness configures the agent **exclusively through `config.json`**: each
launched agent gets an isolated `$XDG_CONFIG_HOME/ddr-acp-agent` with the 0600
`openrouter.key` at the agent's default location and a `config.json` built from the harness
base (LLM endpoint + model) with the scenario's JSON fragment deep-merged over it
(`connect(configJson = """{"tools": {"maxTurnRequests": 3}}""")`). There is no env-shaped
scenario input; a harness-owned sentinel (`ACP_E2E_AGENT_SENTINEL`) is planted in the agent
env to pin the MCP child-env allowlist (`StdioMcpMockServer` probe, `McpConnectorTest`).
`E2eStartupArgumentsTest` pins the process-level argument contract (unknown argument ->
exit 2, a selected profile really is the file
read).

Two system properties wire the harness (`tasks.test` in `build.gradle.kts`):
`acp.agent.binary` (the installDist agent script) and `acp.test.classpath` (the **test runtime classpath**). The second
exists because a Gradle test worker's
`java.class.path` is *not* the test classpath (the worker loads it through its
own classloader), and the stdio MCP mock must be launched as a separate JVM
(`java -cp <testClasspath> ...StdioMcpMockServer`). It is injected through a
`CommandLineArgumentProvider` with a `@get:Classpath` property, so the classpath
stays a task input instead of a stale constant. The harness strips the whole
`DDR_ACP_AGENT_*` family from the spawned agent's environment (it is inert,
and a leaked value must not look like configuration), so scenarios are fully determined by
their `config.json` (`E2eAgentTest`).

- **Wire flow** — initialize/session/config-option/prompt/tool-call/permission,
  model/reasoning switches, the chat request's `reasoning` effort, `usage_update`,
  UUIDv7 messageIds, attribution headers; plus the cheap spec MUSTs:
  **stdout hygiene** (every line the harness consumed on stdout parses strictly as a
  JSON-RPC 2.0 message - a stray `println` is invisible otherwise, the SDK logs
  undecodable lines at trace only), **version negotiation** (the requested v1 is
  echoed, and a v2 request is answered with the agent's latest v1 on a fresh
  connection - the SDK pins the version per connection), and the complete **capability advertisement** (loadSession +
  `sessionCapabilities.list/resume/delete`,
  image/embeddedContext advertised, audio deliberately not, http/sse MCP).
- **Capabilities** — `promptCapabilities` (image/embeddedContext) + multimodal
  conversion (image data URI, inlined resources, `ResourceLink` as a markdown link -
  the baseline prompt content every agent MUST support), `mcpCapabilities` (http/sse),
  AGENTS.md injection, the `Agent build:` hash round-tripped from `git.properties`.
- **Slash commands** — `/info` is advertised on session setup and answered locally (`E2eInfoCommandTest`): one Markdown
  chunk with the resolved config, zero LLM calls, no
  session record, the discarded-text note for extra input, and `/infofoo` still reaching the
  model while `/info` never enters the next request's history.
- **Security & permissions** — path-aware permissions + client fs proxy (in-project
  prompt-free, out-of-project read prompts, the `allow_always` session grant prompting once for
  two prompts' out-of-project reads (the tool-name-keyed semantics)),
  `tools.fsProxy=false` in `config.json` and the **fs-capability negative** (with the
  default `ClientCapabilities()` - `fs` absent - `read_file` + `write_file` run through the
  local store and never call `fs/read_text_file`/`fs/write_text_file`, the spec's "MUST NOT
  attempt to call the corresponding filesystem method" local-store fallback), the **permission
  option contract** (all four `PermissionOptionKind`s offered, distinct non-blank
  ids and names, id/kind pairing via `TestClientOperations.permissionOptions`), run tool
  prompt-free in every mode, run-config tools (build-mode permission prompt, plan-mode absence
  of write tools), move/delete tools (out-of-project move destination prompts), MCP tool
  annotations (`E2eMcpToolPermissionTest` over `MockMcpServer`) and the same over the stdio
  transport (`E2eMcpStdioTransportTest`: the spec-mandatory stdio server as a real child
  process - read-only prompt-free, unannotated prompts + executes, a `read_file`-named server
  tool ignored with a warning while the local `read_file` still runs, an unstartable server
  failing open with only a logged warning) and over the classic SSE transport (`E2eMcpSseTransportTest`: read-only
  prompt-free, unannotated prompts + executes,
  `McpServer.Sse.headers` forwarded), `$/cancel_request` dismissal of a stuck permission
  prompt, mode enforcement (`E2eModeRestrictionTest`: registered tool disabled in the current
  mode is refused before permission/execution; `get_current_mode` prompt-free with the
  governing mode's status text), and the coalesced mode indication (`E2eModeIndicationTest`:
  idle/mid-turn switches emit their client notifications immediately but write one `Mode:`
  system message for the latest mode, at the next prompt start only — never one per flip, none
  for a mode that did not govern a turn; deferred switching itself pinned by
  `E2eDeferredModeSwitchTest`).
- **Sessions** — persistence across three restarts (`session/list` ->
  `session/load` with replay -> `session/resume` -> delete), `update_plan`
  persistence/replay, and the replay outcome contract: one prompt in plan mode
  with a rejecting permission client denies an out-of-project `read_file` and
  an unknown tool call, then `session/load` pins both replayed `tool_call_update`s
  as FAILED with their denial text (never blanket-COMPLETED) and
  the `update_plan` scenario pins the successful call replaying COMPLETED.
  The **cwd gate** is pinned at the unit level (`SessionCwdTest`:
  absolute/existing/directory, plus the unparseable-path refusal;
  `SessionLifecycleTest`: `createSession` refuses and leaves no record; `loadSession`/
  `resumeSession` refuse while the record survives, and an unknown id keeps its own
  refusal) and black-box in `E2eSessionPersistenceTest` (`session/new` with a missing path and
  with a regular file as cwd both fail with invalid params, no record appears).
  The `session/list` `updatedAt` shape is pinned twice: unit-level in
  `SessionLifecycleTest` (a fixed `Europe/Berlin` zone renders summer/winter offsets,
  `+00:00` on a UTC zone, the parsed instant always round-trips) and
  black-box in `E2eSessionPersistenceTest` (regex for a numeric offset +
  `OffsetDateTime.parse(...).toEpochMilli() == record.updatedAt`, timezone-independent
  so it holds on a UTC CI box and in the docker container). The open-session marker is pinned in
  `SessionLifecycleTest` (only live ids carry `_meta`,
  `session/close` clears it and frees the id for a later load, a late
  `onSessionReleased` registration still fires) and black-box in
  `E2eSessionPersistenceTest` (two open sessions marked, `close()` unmarks one and its
  id loads again); `sessionCapabilities.close` is advertised and pinned in
  `E2eWireConformanceTest`. The zombie-prompt contract is pinned unit-level
  in `SessionLifecycleTest` (`prompt`/`setMode` after `delete()` or `close()` fail with
  `-32002`, closing twice stays idempotent, a refused call never resurrects the record)
  and black-box in `E2eSessionPersistenceTest` (the resumed - i.e. active - session is
  deleted, then a prompt is refused with `-32002`, reaches no LLM and leaves no record).
  The graceful-shutdown contract is pinned by `E2eShutdownTest`: a turn
  interrupted by the client closing stdin and a turn ended by `session/cancel` both reach
  the record (user message + pair-complete history, asserted without a follow-up turn), and
  by `SessionLifecycleTest` (an interrupted turn persists what it did; `persistAllSessions`
  flushes live sessions only).
  The title announcement is unit-pinned by
  `SessionStateTitleTest` (announced exactly once, restored
  titles not re-announced, empty-title records announce at the next turn, 72-code-point
  cap) and black-box in `E2eSessionPersistenceTest` (one `session_info_update` with the
  derived title on the first prompt, none on the loaded session's continuation).
  Unit-pinned: `ReplayHistoryTest` (outcome mapping incl. fail-open on unknown values; a captured
  user/tool item - one the lossless parse kept verbatim - replays like its typed
  counterpart), `ToolCallPairingRepairTest` (the pairing repair itself: dangling/answered/batch
  semantics, idempotence, duplicate ids count as answered; the round-trip identity rule -
  fully modeled items re-type, an image part or a multimodal tool output stays captured and
  encodes back byte-for-byte) and `SessionStateRepairTest` (the three view consumers close
  dangling calls while the stored history is never mutated; a real outcome is never
  overridden; a restore reproduces the record's wire items exactly),
  `ToolCallExecutorTest` (outcome recording + immediate persistence, the repeat
  guard: third raw-identical call with the same result refused without execution/prompt,
  a single identical repeat executes, a changed result resets the streak, formatting variants
  pass, reset by an intervening call, denied calls not tracked; permanent permissions:
  allow_always/reject_always arm the map and spare later prompts, allow_once does not,
  restored sessions start without grants) and `PromptRunnerTest` (guard
  integration: the refused third repeat fails the turn's third iteration and the turn continues;
  identical call in a new turn executes again; abnormal-end note chunks: content_filter
  and truncated/wind-down ends always emit their note to the client, streamed partial
  text is never re-emitted),
  `SessionStoreTest` (outcome round-trip + decoding a record without outcomes; the
  stale-temp sweep: a crashed save's temp file is swept while a concurrent save's
  in-flight temp stays, the sweep is throttled to once per interval and resets after
  it), `SessionStateProviderSelectionTest`
  (provider selection round-trip/restore, incl. a wire-level decode of a record without
  the field),
  `SessionConfigOptionsTest` (provider option
  listing/validation/reset/self-heal, manual pick survives an outage, blank/`auto` tag exclusion),
  `ProviderRoutingTest` (provider lookup + median price filtering), `LlmResponseGoldensTest`
  (byte-exact wire documents for the Responses dialect - request items incl. verbatim
  reasoning replay, negative contract: no temperature/max_output_tokens/store/include/
  reasoning.context, error envelope, `incomplete` details) and `PromptRunnerTest`
  (request-level provider choice, reasoning replay to the next iteration, reasoning dropped
  at turn end, abnormal-completion mapping).
- **Agent loop** — the `MAX_TURN_REQUESTS` cap + wind-down synthesis pass, auto
  provider routing (median cap, fail-open, disabled) and the manual provider option (`E2eProviderOptionTest`:
  advertisement, request wire shape, fail-open hidden,
  visible-with-toggle-off + manual-beats-env, persistence).
- **Web fetch** — `web_fetch` over a loopback JDK `HttpServer` (`WebFetchToolTest`):
  HTML conversion, JSON pass-through, paging + footer, declared-binary refusal,
  NUL sniff, gzip, redirects, SSRF block/opt-out, scheme refusal, oversize,
  strict args, HTTP error statuses, tool metadata; the redirect-hop SSRF
  re-validation (public host redirecting into a private host) and the
  special-purpose range table (blocked samples + the adjacent addresses just
  outside every range, so a prefix-mask off-by-one fails; a bracketed IPv6
  literal, and the IPv4-mapped literal as a regression pin) are pinned against
  a ktor MockEngine in `WebFetcherTest` (no DNS/sockets; public IP literals);
  black-box e2e in `E2eWebFetchTest` (prompt-free plan-mode fetch with
  `tools.webFetch.allowPrivate: true`, loopback refusal without it).

The **output caps** (bash/run tail truncation + progressive capture through a drain
timeout, `read_file` limit requirement, bounds incl. the `line`/`startLine` upper bound at
`Int.MAX_VALUE`, no size refusal on windowed reads with `readRaw`'s kept 20 MB
cap, the 64 KB per-line accumulation cap + NUL sniff of `StreamingLineReader` and the
`countVisualLines` byte scan, 2000-char-per-line
truncation and past-EOF error, listing caps, grep match/line caps and the
binary/excluded skips, the `.git` walker skip, the tree-diff walker's `.git` evidence skip) are unit-tested
in
`BashToolTest`/`ReadFileToolTest`/`FileStoreTest`/`ListDirToolTest`/`GrepToolTest`; the
shared numbered-window renderer and the start-line bounds guard are pinned directly in
`VisualLinesTest` (width, CR strip, clamp, `(empty content)`, footer with both
argument label pairs and an unknown total); move/delete
semantics (destination-exists refusal, symlink refusal, dir/file type mismatches, diff
payloads, local-disk-only), the whole-file diff convention and the shared diff policy (store-rendered
write/edit suppressed, local-only ops always emit - pinned by
the `delete_file` fs-proxy flip in `DeleteFileToolTest`), the omitted-diff notes (cap sizes and pre-read errors named in
the result text, in
`WriteFileToolTest`/`EditFileToolTest`/`DeleteFileToolTest`), the tree/move diff evidence (delete+add rename
pairs, the bounded tree caps and the `M of N` note, in
`DeleteDirectoryToolTest`/`MoveFileToolTest`/`MoveDirectoryToolTest` (incl. the 50-file and
aggregate-budget caps and the excluded-file content skip), multi-diff emission
in `ToolCallExecutorTest`; the wire pin `E2eWireConformanceTest` - a `delete_directory`
result carries `{"type":"diff"}` blocks on the raw wire),
the strict JSON-null argument rejections, and the MCP annotation mapping (trusted/untrusted
`readOnlyHint`, kind mapping, `title` annotation) are unit-tested in
the per-tool `*ToolTest` classes (`ReadFileToolTest`, `WriteFileToolTest`,
`EditFileToolTest`, `MoveFileToolTest`, `MoveDirectoryToolTest`, `DeleteFileToolTest`,
`DeleteDirectoryToolTest`)/`PermissionAndFileStoreTest`/`McpBridgeTest` — one test file
per tool source file (`tools/`), shared doubles/helpers in `ToolTestSupport.kt`; the
absent-vs-unreadable split is pinned in `WriteFileToolTest`, the store's regular-files-only
guard in `FileStoreTest`, and the stdio MCP launch contract (env allowlist + client entries
winning) in `McpConnectorTest`. The client fs-proxy windowing contract (no phantom
footer past EOF, whole-file fallback for limit-ignoring clients, EOF-probe arguments +
conservative probe-failure fallback) is pinned in `PermissionAndFileStoreTest` (strict
`SlicingFsClient` / `FailingProbeFsClient` doubles) and `ReadFileToolTest` (rendering). The
calc expression language (operator precedence incl. right-associative `^`,
functions/constants, positional error messages, length/nesting caps) is unit-tested in
`CalcExpressionTest`/`CalcToolTest`. The stale-temp sweep of crashed atomic writes
is pinned in `AtomicWriteTest` (stale temps of the name prefix deleted, fresh
temps and unrelated names kept, symlinks/directories never touched, and the
`writeAtomically` sweep of the target's temp naming).

**Tool schema pins**: every registered local tool pins its `parameters` schema as an
encoded string (`llmWireJson.encodeToString(tool.parameters)`, order-sensitive —
JsonObject equality is order-insensitive) in its own tool test class (`ReadFileToolTest`,
`WriteFileToolTest`, `EditFileToolTest`, `ListDirToolTest`,
`GlobToolTest`, `GrepToolTest`, `MoveFileToolTest`, `MoveDirectoryToolTest`,
`DeleteFileToolTest`, `DeleteDirectoryToolTest` cover the path tools; the remaining
tools in their dedicated classes: `BashToolTest`, `WebFetchToolTest`, `RunToolTest`,
`RunConfigToolsTest`, `UpdatePlanToolTest`, `GetCurrentModeToolTest`, `CalcToolTest`).
The registry-wide guard test
(`ToolSchemaTest.every production tool advertises an object schema…`) iterates
`localTools()` + `sessionTools(cwd)` from `tools/ToolCatalog.kt` — the same helpers
`Main.kt` assembles the production registry from — asserting `type=object`, a
`properties` object and a `required` string array for every tool. It checks
**structure only**: a tool newly added to `ToolCatalog.kt` will pass it until its
schema pin is added, so treat touching the catalog list as the reminder to add the
pin in the same change. The schema builder itself is pinned at the raw-string level by
`ToolSchemaWireTest` (separate from the registry-wide `ToolSchemaTest` guard).

**Sandbox launcher pin table**: the `docker run` composition is a pure function
(`sandbox/DockerInvocation.kt`) over a resolved plan (`sandbox/SandboxPlanResolution.kt`), so
the whole argv is pinned by `SandboxCompositionPinTest`: rows assert composed argv **tokens**
against fixture facts (`uid=1001`, `/fixtures/...`), never host-derived values - the plan is
resolved with `FixtureProbes` (a declared fixture tree), so the test performs no host I/O.
Every `sandbox.*` key, every shared flag (`--profile`) and every forwarded host fact (TZ, tool
caches, git identity) has a named row, and the `--env` allowlist row is the negative closure
of the environment surface (a newly forwarded variable fails a pin instead of slipping
through). Keep the rows in step when touching the composition (a deliberate argv change is a
feature, not a test fix).

**Sandbox test suite** (`src/test/kotlin/.../sandbox/`): the composition, the plan level and
the exec surface.

- `SandboxCompositionPinTest` + `SandboxPinSupport.kt`/`SandboxPinRows.kt`: the pinned argv rows.
- `SandboxPlanTest`: typed plan level - covered paths skipped with typed warnings, writable
  winning over readable, shallowest-first order, a missing configured directory refusing the
  sandbox (the direct-run warning stays in `ConfigTest`).
- `SandboxFailLoudlyTest`: every fail-loudly path (relative/`/`/missing permission and cache
  paths, `ANDROID_*` variants, uncreatable state dir), all before any `docker run`.
- `SandboxSupervisorTest`: the process-level contract against the **installed**
  `acp-agent-sandbox` with the fixture docker on `PATH` - byte-transparent relay,
  stdout/stderr separation, stdin-EOF half-close (the child must finish), signal forwarding,
  exit-code propagation, mask-file cleanup, docker-from-`PATH` (127), strict arguments,
  fail-loudly config, `--profile` forwarding, uid/gid against `id -u`/`id -g`, and the
  project overlay: its directories reach the `--mount` list, a broken overlay or
  one naming a missing directory refuses before any `docker run`.
- `DockerfileBuildInputsTest`: the builder-stage COPY allowlist against the real build inputs
  and the `ENTRYPOINT` path pin.
- `FakeDocker`: the fixture docker CLI (`src/test/resources/sandbox/fake-docker`, installed on
  a fixture `PATH` dir; records every argv losslessly per token, echoes stdin, and offers the
  run-exit / after-EOF / TERM-marker switches).

**e2e change policy**: e2e scenarios pin the agent's **wire contract** — protocol
message flow, security boundaries (permission routing, mode restrictions,
containment caps) and session/replay semantics — not internal orchestration (LLM
call counts, mock filler text, delta chunk splits). A behavioral change
(feature/fix) that requires updating an e2e assertion is expected: update it in the
**same commit** with a one-line comment, preferring a pin of equal strength. An e2e
failure without a behavioral change is a test-design smell: fix the test by
asserting the effect/shape, not the feature. Favor effect-based over exact-count
assertions (e.g. "load/resume make no LLM calls" rather than `equals(2, requestCount)`).
The shared harness therefore offers `assertPairComplete(body, context)` (the pairing
invariant as observed in a chat request) and `collapseConsecutiveDuplicates()`
(streamed deltas -> one entry per contiguous id run) so scenarios pin shapes instead of
chunk counts or positions.

**Known e2e gaps against the ACP spec** (file new issues for new findings, don't just note
them): `tool_call.name` is never emitted and no SDK release up to 0.34.0 can express it -
#64; `session/list` pagination is unexercised (no scenario has >10 sessions, and the SDK
batches at `batchSize = 10` - the capability is marked UNSTABLE upstream and the paging is
SDK-supplied).

Manual validation (the run configurations cannot do this):
- Docker: validate `bash -n bin/acp-agent bin/acp-agent-sandbox` +
  `shellcheck bin/acp-agent bin/acp-agent-sandbox build-docker` (run config `lint_scripts`);
  build with `./build-docker` and smoke-test by piping an `initialize` request into
  `./bin/acp-agent-sandbox` with a `~/.config/ddr-acp-agent/openrouter.key` in place
  (expects a JSON-RPC response on stdout). What the process tests cannot pin without a real
  daemon: the image itself (entrypoint, tool availability) and real bind mounts.
- Direct: smoke-test the same way via `./bin/acp-agent` (keep stdin open briefly after the
  request — closing it immediately races the transport teardown and swallows the response).
- Client UI: the e2e harness sees the wire but not the IDE, so anything rendered
  client-side is verified by hand in the JetBrains client (session setup, type `/` and
  look for the agent's advertised command). `/info` is the confirmed case (listed and
  autocompleted); tool-call titles are the other surface with no automated pin.

**ACP SDK upgrade checklist**: on every `com.agentclientprotocol:acp` bump, read the
release notes (github.com/agentclientprotocol/kotlin-sdk/releases) and re-verify against
the exact resolved sources (`sources` unpacks `acp-jvm-<version>/` + `acp-model-jvm-<version>/`):
(1) `model/SessionUpdate.kt` - does `SessionUpdate.ToolCall` carry the programmatic `name`
property yet? If so, emit it (`tool.name` in `ToolCallExecutor`, the persisted
`call.function.name` in `ReplayUpdates`) and close issue #64; (2) cancellation semantics (`agent/Agent.kt`
`SessionWrapper.prompt`) - a cancelled turn must still be answered with
`PromptResponse(StopReason.CANCELLED)`, which `E2eCancelTest` pins; watch
`protocol/Exceptions.kt` for new error types in the permission path (`AcpRequestCancelledException` is deliberately
*not* a `CancellationException`); (3) `AgentSupport` contracts - `deleteSession` must keep succeeding for
unknown/already
deleted ids (pinned by `AgentSupportImplTest`), and `session/list` pagination over our
`Sequence` override (10 per page + `nextCursor`); (4) symbol stability - the JSON-RPC error
helpers (`AcpExpectedError`, `JsonRpcException`, `acpFail`, `jsonRpcInvalidParams`,
`ACPJson`) live in `Exceptions.kt` with their packages unchanged; expect file moves, watch
for renames.

**Definition of done**: a change is done when the `build` run configuration passes
(compile + all tests incl. the black-box e2e).

## Pitfalls and learnings

- **stdout is the protocol stream** (see Boundaries): the only legitimate stdout write is the
  transport output lambda in `Main.kt`. kotlin-logging 8.x prints an "initializing..." banner on
  first logger creation unless `KotlinLoggingConfiguration.logStartupMessage = false` is set
  first - `main()` in `Main.kt` does this (together with
  `org.slf4j.simpleLogger.logFile=System.err`) before `runAgent` creates the first logger; do not
  move a top-level `val logger = KotlinLogging.logger {}` ahead of it.
- **Assistant history items need `id` and `status`.** The Responses input rejects an assistant
  `message` without them, so a provider item is captured verbatim and never rebuilt, while a
  synthesized one (continue note, wind-down summary, interrupted/content-filter note) carries a
  generated `msg_<uuidv7>` id and `status: "completed"`. Pinned by `PromptRunnerTest`.
- **LLM wire JSON settings are load-bearing.** The shared `llmWireJson` (`llm/LlmWire.kt`)
  must keep `namingStrategy = JsonNamingStrategy.SnakeCase` (without it `call_id` /
  `output_tokens` silently fail to parse) plus `ignoreUnknownKeys = true`,
  `explicitNulls = false`, `encodeDefaults = true`. Do not create a second `Json` for LLM
  wire-shaped data with different settings; `LlmResponseGoldensTest` pins the request shape
  and the session store reuses the same instance for persisted history.
- **Stale e2e binary**: the e2e harness (`net.dontdrinkandroot.acpagent.e2e`) drives the installed agent, and `test`
  depends on `installDist`. Running a single test from the IDE against an old
  install validates stale sources - re-link (`installDist`) first.
- **A test reading a checkout file at runtime is not rerun when that file changes.** The
  `test` task's inputs are the compiled classes/classpath, not files the Java code opens at
  runtime - a test that launches a checkout script keeps the old file under an UP-TO-DATE /
  cached run. Rerun with `--rerun-tasks` (run config `test_fresh`) after editing such a file,
  or declare it as a task input.
- **Sandbox test fixtures live under `build/`** (never `/tmp` or `/home/dev`): `/tmp` and the
  container home are the covered roots the permission-mount skip policy prunes, and `/home/dev`
  exists only inside the container. `SandboxSupervisorTest`'s fixtures follow the same rule;
  the pin table itself is marker-free (`FixtureProbes` answers from a declared tree).

- **Gradle build cache can report a false green**: outputs are cacheable, so a
  `test`/`build` run that *just changed test sources or the classpath* may come
  back `FROM-CACHE`/`UP-TO-DATE` and hide failing tests (an earlier run's
  up-to-date-looking output is replayed). When a
  run reports no executed test task (`test FROM-CACHE` / `UP-TO-DATE` with no
  `:test` execution line), distrust it: rerun with `./gradlew clean test
  --tests <Class>` or the `test_fresh` run config (`--rerun-tasks`) before
  believing a green. Conversely a fresh-looking `test` line means it really ran.
- **ktor client-side redirect plugin vs. the manual redirect loop**: any client
  used with `WebFetcher` (production `defaultClient` and every test client) must
  set `followRedirects = false`. The plugin is on by default, consumes 30x
  responses itself, and silently bypasses the per-hop SSRF re-validation (a MockEngine test without it follows the
  redirect and the loop never
  sees the `Location` header).
- **JDK chunked `InputStreamReader` can spin forever**: a read-loop that calls
  `reader.read(chunk)` and processes chars incrementally burns 100% CPU forever on
  zero-char reads under JDK 25 (`jstack` on the Gradle test worker shows
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
- **Gradle lock timeout**: `Timeout waiting to lock ...` means an OS *flock* on a lock file is
  held - the blocker is real contention, not the lock file's existence: idle daemons keep
  `modules-2.lock` open without holding it (`fuser` lists them although the lock is free),
  and every lock file keeps the last owner's PID as a record after release, so neither
  `fuser` nor the recorded PID proves contention; `flock -n <lock> true` does. **Root cause**:
  Gradle hands a held lock over only when the
  waiter *asks*: the waiter pings the owner over **loopback UDP** (`FileLockCommunicator`),
  the owner releases and the waiter proceeds. That ping cannot cross network namespaces,
  so a container on a bridge/named network (a different netns than the host) waits out the
  ~60s lock timeout and fails instead of queuing - even though the kernels' flocks would
  serialize correctly. Gradle declares this unsupported and will not change the protocol (gradle/gradle#851, #8375; the
  timeout is deliberately not configurable). **The supervisor
  therefore runs the container in the host network namespace by default** (`--network host`,
  `sandbox.network` for an opt-out), which puts host and container Gradle processes on
  one loopback and restores the handover. The shared
  cache leaves still form one lock domain across the host and all agent containers, so a
  `Timeout waiting to lock` now only appears in the opt-out (bridge/named-network) mode or
  for a genuinely hung owner; a retry is safe, and the work share is the reason the cache is
  shared at all. First aid: `./gradlew --stop` (container-local daemons only), then classify
  the lock file (`flock -n <lock> true` proves contention; `fuser`/`lsof` show a visible
  holder): nothing flocked means "just retry", a visible holder means wait or stop it, and a
  flock without a visible process means a build on the shared **host** `GRADLE_USER_HOME` -
  escalate to the user. Never hand-delete a lock file that is still flocked (a waiter would
  recreate it and two owners would share the cache); wait-and-retry is the gentler
  alternative when the owning build is expected to finish soon.

## Recipes

- **Connect an MCP server** - all three transports work on JVM (`mcp/McpConnector.kt`): stdio via
  `ProcessBuilder` + kotlinx-io
  `asSource()/asSink().buffered()`; streamable HTTP and classic SSE via
  `HttpClient(CIO){ install(SSE) }`. Each has an e2e mock to copy from when
  pinning new MCP behavior: `MockMcpServer` (HTTP), `StdioMcpMockServer` (stdio, separate
  JVM) and `MockMcpSseServer` (SSE) - all sharing `MockMcpTools`.
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

## Self-Update Instruction

This guidelines file is a living document and MUST be actively maintained by the LLM Agent.

* **Trigger:** Whenever significant changes are made to the project that are related to any information provided here,
  the LLM Agent MUST immediately update this file (`AGENTS.md`) to reflect the current state of the project.
* **Budget:** Keep the file below 500 lines / 24 KiB. Review this budget after every change of this file.
* **Content:**
  * Add any information that could have helped the agent to solve the task more efficiently or in fewer steps.
  * Remove outdated, obsolete, or incorrect information.
  * Ensure all tech stack versions and library names are accurate.
  * Make sure the most important features are clearly documented, but in a compact and concise manner.
  * Keep the project structure up to date so that the most important files and directories are visible at a glance, no
    need to document every file and directory, rather give the Agent a hint of the general project structure and where
    it will find things.
* **No history:** this file documents the current state, not how it came about. Git holds the history, so do not keep
  changelog-style narration (what was removed/renamed/fixed, "previously", "used to", upgrade logs, closed-issue
  recaps). Keep only what still guides a decision: the rationale ("why") of a design choice, boundaries, and the
  constraints a future change must respect. When you touch a paragraph, prune its history in the same edit.
* **Protected:** Never update the "Conventions" or "Self-Update Instruction" section without being explicitly instructed
  to do so.
* **Proactivity:** Do not wait for explicit instructions to update these guidelines if you identify a discrepancy
  between the guidelines and the actual codebase.
