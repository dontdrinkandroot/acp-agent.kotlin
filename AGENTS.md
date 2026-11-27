# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

This repository (`acp-agent.kotlin`) holds `acp-agent`, a coding agent that talks to JetBrains
IDEs exclusively over the **Agent Client Protocol (ACP)** - JSON-RPC 2.0 / NDJSON over stdio.
There is **no GUI/TUI**: the IDE launches the agent as a subprocess and drives it via
`stdin`/`stdout`. It acts strictly as an ACP **agent** - it only *consumes* ACP, never acts as a
client/IDE, and only *consumes* MCP servers. The target platform is Linux only.

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
| HTTP             | ktor client (CIO engine), ktor-client-encoding (gzip/deflate for `web_fetch`)           | 3.6.1                        |
| HTML parsing     | jsoup (HTML -> line-based text for `web_fetch`; zero runtime deps)                      | 1.23.2                       |
| Coroutines       | kotlinx-coroutines-core                                                                 | 1.11.0 (declared + resolved) |
| IO               | kotlinx-io-core                                                                         | 0.9.2                        |
| JSON             | kotlinx-serialization-json                                                              | 1.12.0 (declared + resolved) |
| LLM wire models  | owned (`llm/models/ResponsesWireModels.kt`, Responses API, no LLM framework)            | -                            |
| UUIDv7 ids       | com.github.f4b6a3:uuid-creator (zero deps; `UuidCreator.getTimeOrderedEpoch()`)         | 6.1.1                        |
| Logging          | kotlin-logging via slf4j-simple                                                         | 8.0.4 / 2.0.20               |

`8.0.4` is pinned explicitly: ktor/ACP pull 7.0.0 and older 8.x, and 8.0.4 carries the
banner-suppressing API.

## Boundaries (do not silently change)

If a boundary looks contra-productive, overcomplicated or blocking, say so instead of working
around it. Each names the capability spec that owns it (`openspec/specs/<name>/spec.md`).

- **stdout carries the protocol.** Every byte must be an ACP JSON-RPC line; all logging goes to
  stderr (`agent-process`, plus the pitfall below).
- **`terminal/*` is not implemented on purpose.** The agent runs its own processes instead of
  delegating to the client's terminal and keeps ignoring the `terminal` capability.
- **Permissions are decided by path, never by trust** - cwd, the matching directory tier or (for
  reads) the scratch dir; `unlistedPaths = deny` refuses instead of prompting (`permissions`).
- **One LLM provider:** OpenRouter's Responses API. `llm/models/ResponsesWireModels.kt` is the
  internal LLM contract; `LlmClient` is transport only (`llm-streaming`).
- **Wire items stay raw.** History items are `JsonObject`s, re-typed only when the typed value
  re-encodes identically, so a restored session replays byte-for-byte (`session-lifecycle`).
- **Reasoning is live-only**: replayed within a turn, cleared on every exit path, never
  persisted (`agent-turn`).
- **The request shape is a negative contract:** no `temperature`, `max_output_tokens`, `store`,
  `previous_response_id`, `include`, `reasoning.context` (`llm-streaming`).
- **The system prompt is a cached prefix.** Caches match a prefix, so a head change re-bills the
  conversation and can re-route OpenRouter's sticky provider (no `session_id` is sent). Durable
  inputs only - build, cwd, date, AGENTS.md, `.ai/run.json`, tiers - in stable order; per-turn
  facts (mode, tools, plan) go into the conversation (`system-prompt`).
- **No session-record migration.** An old-dialect record decodes but fails at its first turn; do
  not add a compat shim (`session-lifecycle`).

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
`.ai/scripts/show-test-failures.sh` in `timeout` (180s fast loop / single class, 300s
`build`/`test`), so a hung daemon surfaces as a timeout. The configurations are **not listed
here** - they are rendered into the system prompt from `<cwd>/.ai/run.json` per turn, so `run` is
the source of truth; manage them with the `*_run_config` tools, and read the assembled prompt
with `system_prompt` (`agent/SystemPromptDump.kt`).

Launch paths (checked in, the only supported ones; the only argument is `--profile <name>`,
everything else comes from the selected `config.json`):

- `bin/acp-agent` — direct (no sandbox): `installDist`, then execs the agent
- `bin/acp-agent-sandbox` — `installDist`, ensures the image (`--rebuild` forces a local build, a
  manual `docker pull` updates it; the wrapper never pulls), then execs the supervisor, which
  relays the container's stdio byte-for-byte

Docker is resolved from `PATH` everywhere; an IDE-inherited `PATH` without docker is a hard
failure.

**Shutdown**: `runAgent` waits for `Transport.State.CLOSED`, then cancels pending incoming
requests, flushes live sessions (`persistAllSessions`), then closes the protocol - deliberately
not driven from the transport's `onClose` (`agent-process`).

## Tool Usage

* **Web research**: use `web_search_exa` then `web_fetch_exa` for anything you are unsure about -
do not guess, and verify API contracts, library versions and upstream behavior against primary
sources (cite what you checked).
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
summary here; observable behaviour is under `openspec/specs/`, one-off detail in KDoc.

- `Main.kt`, `BuildInfo.kt` - logging setup + strict argument parsing, `runAgent` (transport,
  registries, session factory, shutdown flush), build hash + version.
- `config/` - strict `config.json` parsing, `--profile`, the project overlay, key resolution, XDG.
- `llm/` - the Responses-API transport (hand-rolled SSE scan), owned wire models,
  `ChatCompleter`; `providerrouting/` - auto routing and its endpoints cache.
- `agent/` - the session: record/store, state, mode machine, prompt runner, tool-call executor,
  system prompt, permission policy, replay, `/info`.
- `mcp/` - MCP consumption (stdio, streamable HTTP, classic SSE) and tool bridging.
- `sandbox/` - the supervisor: host facts -> typed plan -> argv composition.
- `tools/` - the tool set + `ToolRegistry`/`ToolCatalog`, `FileStore`, walkers, caps, exclusion
  and diff policies.
- `src/test/.../sandbox/` - argv pin table (`SandboxPinRows`), plan/fail-loudly/supervisor/
  Dockerfile tests, `FakeDocker`; `src/test/.../e2e/` - the black-box harness and its mocks;
  `src/test/.../spec/` - `SpecPinsTest`, the gate over the OpenSpec pin index.
- `bin/`, `build-docker`, `Dockerfile` - launch wrappers, local image build, multi-stage image.
- `.ai/run.json`, `.ai/scripts/` - run configurations and `show-test-failures.sh`.
- `README.md` - user-facing docs; `openspec/` - the behaviour specs and in-flight changes.

## Specs (OpenSpec)

`openspec/specs/<capability>/spec.md` is the agreed behavior - the source of truth for what the
agent does. `openspec/changes/<name>/` is one unit of work: `proposal.md` (why, what, which
capabilities), `specs/<capability>/spec.md` as a **delta**, optional `design.md` (only real
decisions), `tasks.md`. `specs/` is written **only** by archiving - never edit it by hand during a
change, and never restate unchanged requirements in a delta.

Drive the CLI through the `spec_*` run configurations (`spec_list`, `spec_status`,
`spec_instructions`, `spec_validate` read; `spec_new_change`, `spec_archive` mutate); the folder
is plain Markdown, so `list_dir`/`read_file` also answer "which artifact is next".
`openspec/changes/archive/` is gitignored - an archived change stays local: what it decided is
merged into `openspec/specs/`, so the folder is working material, not a record. Durable reasoning
belongs in the open change's `design.md`, in a boundary here, or in KDoc.

Workflow: GitHub issue -> *propose* writes the change folder, no source code -> user reviews ->
*apply* works `tasks.md`, ticking each box -> archive, close the issue with a comment on the
chosen approach. While a change is applied, its `tasks.md` is the progress record; `update_plan`
mirrors only the current task group.

`spec_validate` enforces the shape: `#### Scenario:` (four hashes) with a WHEN/GIVEN trigger and
a THEN outcome, requirement text under 500 characters, exact MODIFIED/REMOVED names,
`skip_specs: true` for a change without spec-level effect; in `tasks.md` only a checkbox counts.

Each requirement is pinned by a test: its scenarios carry `- **Pinned by**` lines until the
requirement is converted, after which `@Pins("<capability>", "<requirement>")` on the test class
is the only anchor. **Never author a new pin line** - convert whenever work touches the
requirement. A scenario no test can pin says why instead (upstream SDK, missing environment,
human judgement). `SpecPinsTest` gates both modes: every pin resolves to a compiled class that
runs a test, every requirement has one, and the index lands in `build/reports/spec-coverage.md`.
It checks that a pin *resolves*, never that it asserts; `openspec/**` is a declared `test` input.

## Behaviour map

- **Runtime** - `agent-process` (startup, capabilities, `/info`, shutdown), `configuration`
  (files, profiles, parser, key file, XDG, overlay), `docker-sandbox` (relay, mounts, hardening).
- **Sessions** - `session-lifecycle` (create/cwd gate, record, replay, list, title, pairing, end),
  `agent-turn` (budget, wind-down, abnormal completions, repeat guard, cancellation),
  `system-prompt`, `prompt-content`.
- **Models & integrations** - `model-configuration` (options), `llm-streaming` (SSE, request
  shape), `provider-routing` (auto policy), `plan-tracking`, `mcp-consumption` (transports,
  bridging, stdio launch contract).
- **Tools** - `file-tools`, `command-execution` (`bash`), `run-configurations`, `web-fetch`,
  `utility-tools` (`calc`, `get_current_mode`), `permissions`, `tool-call-presentation`.

## Testing

Drive the suites through the run configurations: `test` (whole suite), `test_class` (one
class/method via `[args]`; re-links installDist) and `show_failures` (last run's failures).

Unit tests plus a **black-box e2e harness** (`net.dontdrinkandroot/acpagent/e2e`, sharing the
`E2eAgentTest` base): it runs the linked `installDist` agent as a separate OS process with the
real ACP SDK client and a local mock OpenRouter server, pinning the agent's **wire contract**.
The harness configures the agent **exclusively through `config.json`** (isolated
`$XDG_CONFIG_HOME/ddr-acp-agent` with the 0600 `openrouter.key` and a deep-merged config, e.g.
`connect(configJson = """{"tools": {"maxTurnRequests": 3}}""")`); no env-shaped input. A sentinel
(`ACP_E2E_AGENT_SENTINEL`) pins the MCP child-env allowlist, and two system properties wire the
harness (`tasks.test`): `acp.agent.binary` and `acp.test.classpath` (the **test runtime
classpath** - not a worker's `java.class.path`; the stdio MCP mock runs as a separate JVM).

**Tool schema pins**: each local tool pins its `parameters` schema as an encoded string
(`llmWireJson.encodeToString(tool.parameters)`, order-sensitive) in its own test class.
`ToolSchemaTest` checks structure over `ToolCatalog.kt`'s tool lists - pin a new tool in the same
change; `ToolSchemaWireTest` pins the builder.

**Sandbox pin table**: the `docker run` argv is a pure function (`sandbox/DockerInvocation.kt`
over `sandbox/SandboxPlanResolution.kt`), pinned by `SandboxCompositionPinTest` in named rows
against fixture facts (`uid=1001`, `/fixtures/...`; `FixtureProbes`, no host I/O). Every
`sandbox.*` key, flag and forwarded host fact has a row; the `--env` row is the negative closure
of the environment surface - argv changes are features, not test fixes (`SandboxPlanTest`,
`SandboxFailLoudlyTest`, `SandboxSupervisorTest`, `DockerfileBuildInputsTest` around it).

**e2e change policy**: e2e scenarios pin the **wire contract** - protocol flow, security
boundaries, session/replay semantics - not internal orchestration (call counts, mock filler, chunk
splits). A behavioral change may need an assertion update: same commit, one-line comment, a pin of
equal strength; an e2e failure without a behavioral change is a test-design smell. Prefer
effect-based assertions ("load makes no LLM calls") over exact counts.

**Manual validation** (outside the run configurations): `lint_scripts` for `bash -n` +
`shellcheck` on the wrappers and `build-docker`; then smoke-test both launch paths by piping an
`initialize` request into them (keep stdin open briefly) with the key file in place. Client-side
rendering (`/info`, tool-call titles) is verified by hand in the JetBrains client.

**ACP SDK upgrade checklist** (`com.agentclientprotocol:acp`): read the release notes and
re-verify against the resolved sources (`sources` unpacks `acp-jvm-<version>/`): (1)
`model/SessionUpdate.kt` - the v1 `SessionUpdate.ToolCall` has no programmatic `name` member, so
that absence is pinned on the raw wire only (issue #64); once the model carries it, emit it
(`ToolCallExecutor`, `ReplayUpdates`), drop the rule from `tool-call-presentation` and close #64;
(2) cancellation (`agent/Agent.kt` `SessionWrapper.prompt`) - a cancelled turn must answer
`PromptResponse(StopReason.CANCELLED)` (`E2eCancelTest`); watch `protocol/Exceptions.kt`
(`AcpRequestCancelledException` is deliberately *not* a `CancellationException`); (3)
`AgentSupport` - `deleteSession` must keep succeeding for unknown ids (`AgentSupportImplTest`),
`session/list` paging over our `Sequence` (10 + `nextCursor`, unexercised); (4) symbol stability
of the JSON-RPC helpers (`AcpExpectedError`, `JsonRpcException`, `acpFail`,
`jsonRpcInvalidParams`, `ACPJson`).

## Pitfalls and learnings

- **stdout is the protocol stream**: the only legitimate stdout write is the transport output
  lambda in `Main.kt`. kotlin-logging 8.x prints an "initializing..." banner on first logger
  creation unless `KotlinLoggingConfiguration.logStartupMessage = false` is set first - `main()`
  does this (with `org.slf4j.simpleLogger.logFile=System.err`) before `runAgent` creates the
  first logger; do not move a top-level `val logger` ahead of it.
- **Assistant history items need `id` and `status`.** The Responses input rejects an assistant
  `message` without them, so a provider item is captured verbatim, while a synthesized one
  (continue note, wind-down summary, interrupted/content-filter note) carries a generated
  `msg_<uuidv7>` id and `status: "completed"` (`PromptRunnerTest`).
- **LLM wire JSON settings are load-bearing.** The shared `llmWireJson` (`llm/LlmWire.kt`) keeps
  `namingStrategy = JsonNamingStrategy.SnakeCase` (without it `call_id`/`output_tokens` silently
  fail to parse), `ignoreUnknownKeys`, `explicitNulls = false`, `encodeDefaults = true`. Do not
  create a second `Json` for LLM wire data: `LlmResponseGoldensTest` pins the request shape and
  the session store reuses the instance.
- **Stale e2e binary**: a single test run from the IDE against an old `installDist` validates
  stale sources - re-link first.
- **A file read at test runtime is not an input by default** - a spec or `Dockerfile` edit would
  leave `test` UP-TO-DATE. `test_fresh` reruns everything; declare the file as an input (as the
  `Dockerfile` and `openspec/**` do).
- **Sandbox test fixtures live under `build/`** (never `/tmp` or `/home/dev` - covered roots the
  mount skip policy prunes, and `/home/dev` exists only in the container).
- **Gradle build cache can report a false green**: a `FROM-CACHE`/`UP-TO-DATE` run right after a
  test edit can hide failures - distrust a run without a `:test` execution line, use `test_fresh`.
- **ktor client redirect plugin vs. the manual redirect loop**: any client used with `WebFetcher`
  must set `followRedirects = false`; the plugin is on by default, consumes 30x responses itself
  and silently bypasses the per-hop SSRF re-validation.
- **JDK chunked `InputStreamReader` can spin forever**: `reader.read(chunk)` burns 100% CPU on
  zero-char reads (JDK 25; `jstack` shows `RUNNABLE` in `StreamDecoder.read`). The file line
  reader splits on `\n` bytes and decodes per line; take a `jstack` before blaming Gradle.
- **CIO engine request timeout**: the ktor CIO engine applies a default **15s aggregate
  `requestTimeout`** unless disabled (`engine { requestTimeout = 0 }`), silently killing any
  completion that streams longer (`Request timeout has expired [url=..., request_timeout=unknown
  ms]` - it looks like a server error but is client-side). Only SSE-plugin requests are exempt;
  `LlmClient` disables it and guards with a 120s socket idle timeout.
- **SDK session state reporting**: `asModeState()` builds the session response from
  `defaultMode` + `availableModes` (not the live `currentMode`), so a restored session must set
  `defaultMode` to its persisted mode.
- **Client-side error surface**: the official SDK client rethrows JSON-RPC errors as
  `AcpExpectedError` (message only); agent-side direct calls (unit tests) see `JsonRpcException`
  for `jsonRpcInvalidParams`.
- **Gradle lock timeout**: `Timeout waiting to lock ...` means an OS *flock* is held; idle
  daemons keep `modules-2.lock` open without holding it and lock files keep the last owner's PID,
  so only `flock -n <lock> true` proves contention. Gradle hands a lock over only when the waiter
  *asks*, via a **loopback UDP** ping (`FileLockCommunicator`) that cannot cross network
  namespaces - hence the supervisor's default `--network host` (gradle/gradle#851, not
  configurable). First aid: `./gradlew --stop`, then classify: nothing flocked = retry, a visible
  holder = wait or stop it, a flock without a visible process = a build on the shared **host**
  `GRADLE_USER_HOME` (escalate). Never hand-delete a flocked lock file.

## Recipes

- **Add a tool** - implement `AgentTool` (`tools/Tool.kt`: `name`, `description`, JSON-schema
  `parameters`, `kind`, `mutating`, optional `modes`, `execute`), register it in `ToolCatalog.kt`
  (`Main.kt` assembles the registry from those lists), add the schema pin and update
  `openspec/specs/` in the same change. For MCP pinning, copy a mock: `MockMcpServer` (HTTP),
  `StdioMcpMockServer` (stdio, separate JVM) or `MockMcpSseServer` (SSE).

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
