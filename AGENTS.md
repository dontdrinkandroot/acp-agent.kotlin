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
| ACP                    | `com.agentclientprotocol:acp`                                                         | 0.30.1                            |
| MCP                    | `io.modelcontextprotocol:kotlin-sdk-client`                                           | 0.15.0                            |
| HTTP                   | ktor client (CIO engine)                                                              | 3.5.1                             |
| Coroutines             | kotlinx-coroutines-core                                                               | 1.11.0 (resolved)                 |
| IO                     | kotlinx-io-core                                                                       | 0.9.1                             |
| JSON                   | kotlinx-serialization-json                                                            | 1.11.0 (resolved; declared 1.9.0) |
| OpenRouter wire models | ai.koog:prompt-executor-openrouter-client (models only, no framework)                 | 1.2.0                             |
| Logging                | kotlin-logging via slf4j-simple                                                       | 8.0.4 / 2.0.17                    |

Gradle conflict resolution overrides our declared kotlinx versions (Koog forces the
resolved ones); that is expected. Koog is used as a wire-model / serialization library
only (no framework); its OpenAI-compat wire types are the internal LLM contract (see the
LLM provider bullet below). kotlin-logging 8.0.4 is declared explicitly because
Koog/ktor/ACP pull 7.0.0 and 8.0.01 transitives; pinning 8.0.4 pins the
banner-suppressing API.

## Building / running

```bash
./gradlew compileKotlin          # compile main (fastest loop)
./gradlew installDist            # produce build/install/acp-agent.kotlin/bin/acp-agent.kotlin
./ddr-acp-agent                  # run the agent directly (no docker); auto-rebuilds via
                                 # installDist when src/ or the build scripts are newer
                                 # than the binary, then execs the installDist launcher
./gradlew test                   # unit tests + black-box e2e (drives installDist launcher)
./gradlew test --tests "net.dontdrinkandroot.acpagent.llm.LlmRequestTest"   # single test class (re-links installDist; IDE-only runs may use a stale binary - see Pitfalls)
./gradlew build                  # assemble + test
```

The launcher takes no args; env config only (`OPENROUTER_API_KEY` required).

**Shutdown**: `runAgent` loops until `transport.state.value == Transport.State.CLOSED`
(small delay) then `protocol.close()`.

### Configuration (OpenRouter + MCP)

Config comes from environment variables:

- `OPENROUTER_API_KEY` (required)
- `OPENROUTER_MODEL` (default `openrouter/auto`)
- `OPENROUTER_BASE_URL` (default `https://openrouter.ai/api/v1`)
- `OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED` (default enabled; `0` disables
  the automatic provider routing, see Features)
- `FS_PROXY_ENABLED` (default enabled; `0` uses the local store even when the
  client advertises fs capabilities, see Features)
## Features

- **ACP agent lifecycle**: `initialize` -> `session/new` (random session id) ->
  `session/prompt` -> `session/delete` (closes MCP connections + `LlmClient`, removes the
  persisted record); `session/cancel` relies on coroutine cancellation.
- **Session persistence**: sessions persist to
  `$XDG_STATE_HOME/ddr-acp-agent/sessions/<sessionId>.json` (fallback
  `~/.local/state`), atomically written (temp file + move) on every completed
  prompt turn (END_TURN / MAX_TURN_REQUESTS) and on mode changes. The record (`agent/SessionRecord.kt`) covers history
  (Koog `OpenAIMessage` wire types,
  serialized by the shared `llmWireJson`), mode, title (derived from the first
  user message, truncated to 72 code points) and a last-activity timestamp.
  Persist and delete share a `persistMutex` so a delete can never interleave
  with an in-flight persist and resurrect the record; persistence failures are
  logged to stderr and never fail the turn. Client-supplied session ids are
  format-checked (`isValidSessionId`: `sess_` + 16 lowercase hex digits, exactly
  what `randomSessionId` mints) before they reach the store, so traversal or
  separators in `session/load`/`resume`/`delete` are rejected with invalid-params.
- **Session restore**: `initialize` advertises `loadSession: true` plus
  `sessionCapabilities.list/delete/resume`. `session/load` restores a session (reconnecting MCP servers, replaying
  history as `user_message_chunk` /
  `agent_message_chunk` / pending `tool_call` + completed `tool_call_update`
  updates; replay is emitted from `AgentSession.postInitialize()`, i.e. after
  the load response - an SDK hook limitation, so clients receive it slightly
  later than the response). `session/resume` restores without replay.
  `session/list` filters by cwd and sorts most recent first (corrupt records are
  skipped). A request cwd mismatch, unknown/invalid ids and double-loads are
  invalid-params errors; a corrupt record maps to an internal error.
- **Agent loop**: per prompt, up to 20 LLM iterations; assistant text is streamed to the
  client as `AgentMessageChunk` as it arrives; streamed tool-call deltas are merged;
  executed tool results are appended to the history as tool messages; stops on
  `END_TURN` (no tool call requested) or at the cap (`MAX_TURN_REQUESTS`).
- **Session modes (plan/build/bash)** - read-only `plan` default; `build` enables edits;
  `bash` adds the bash tool. Tools carry `modes`; `ToolRegistry.availableForMode(mode)`
  and `disabledInMode(name, mode)` give the mode-aware "disabled in current mode" error.
  The mode-aware system prompt is rebuilt per turn; `todayProvider` (via
  `java.time.LocalDate`) and `cwd` are injected per session, and the build
  commit hash (see below) appears as `Agent build: <sha>[-dirty]` so the agent
  (and by extension the user) can tell which build it is running. Config option: a single
  `mode` select (`category: mode`, live `currentValue`); `session/set_config_option` +
  legacy `session/set_mode` handled; both emit `current_mode_update` + full
  `config_option_update`; unknown -> invalid-params error.
- **Build hash**: the `generateGitProperties` Gradle task writes
  `git.properties` (`git.commit=<short sha>` or `<sha>-dirty` when the working
  tree is not clean, `unknown` outside a git checkout) into the main resources
  (wired via `sourceSets.main.resources.srcDir`); `BuildInfo.kt` (root package)
  reads it from the classpath. Docker builds have no `.git` (`.dockerignore`),
  so the hash is injected as the `GIT_SHA` build-arg (Dockerfile `ARG` ->
  env var honored first by `build.gradle.kts`): `build-docker` computes short
  sha + `-dirty` from the working tree, `build-image.yml` from the checkout.
- **Model + reasoning config options**: `session/new`/`load`/`resume` fetch the
  OpenRouter model feed (`GET /models`, `llm/LlmModels.kt` wire types; filtered to
  tool-capable text-output models, sorted by id; fetch failure fails session creation, as
  in the Go agent). Sessions carry a per-session `model` (default: env
  `OPENROUTER_MODEL`) and `reasoning` effort (`category: thought_level`, from each
  model's `reasoning` block; `supported_efforts` empty -> gateway levels
  max..minimal; `mandatory` models drop the `none` option; `none` maps to omitting the
  request field). `session/set_config_option` (`model`/`reasoning`) + legacy
  `session/set_model` handled; a model switch resets reasoning to the new model's
  default; every change emits `current_mode_update` + full `config_option_update` and
  persists. The chat request carries `reasoning: {effort: "..."}` (own request wire
  type in `LlmClient`, not a Koog model).
- **Plan updates**: the `update_plan` tool (`tools/PlanTool.kt`, kind `think`, non-mutating,
  available in every mode) emits ACP `PlanUpdate` updates for the client UI and stores the
  entries on the session for persistence; entries are decoded into the SDK's typed
  `PlanEntry` (strict enum validation - a deliberate deviation from the Go raw-string
  passthrough); replay appends the plan update after the history replay.
- **Usage indicator**: after a completed model call the agent emits a `usage_update`
  (`used` = prompt tokens of that call, `size` = the model's `context_length`); skipped
  when the model reports no usage or context length. During an active prompt the SDK
  client routes session updates into the prompt event flow, not the operations
  `notify` callback.
- **Tools**: local file tools (`read`/`write`/`edit`/`list`/`glob`/`grep`, kotlinx-io)
  plus `bash` (`ProcessBuilder`) and `update_plan` (`tools/PlanTool.kt`), registered in
  `Main.kt` and copied per session; MCP tools are bridged per session (`mcp/McpBridge.kt`)
  into the same registry. `read_file`/`write_file`/`edit_file` operate on a
  per-session `tools/FileStore.kt` backend - the ACP client's fs proxy (`fs/read_text_file`/`fs/write_text_file`,
  unsaved editor state + reviewable diffs)
  when the client advertises read+write fs capabilities and `FS_PROXY_ENABLED` is not
  `0`, otherwise a local store; `list_dir`/`glob`/`grep` always read the local disk (electing the local store even when
  a proxy is active, since ACP has no client-side
  listing/search).
- **Permissions (path-aware)**: path-scoped file tools (`read_file`, `write_file`,
  `edit_file`, and the `root` of `glob`/`grep`/`list_dir`) run without asking while
  they stay inside the session working directory; anything that reaches outside the
  project - reads and writes alike - and any non-path mutating tool (`bash`) asks via
  `session/request_permission`. In-project writes are further gated by mode (write
  tools only exist in build/bash; plan is read-only). The containment check (`isWithin`/`resolveAgainstSessionCwd` in
  `tools/Containment.kt`) is symlink-safe
  and resolves relative paths against the session cwd. `allow_always`/`reject_always`
  persist per session in `AgentSessionImpl.permanentPermissions` (keyed by tool name);
  `allow_once`/`reject_once` apply to a single call.
- **MCP consumption**: MCP servers come exclusively from the client's
  `session/new` `mcpServers`; all three transports work on JVM (see Recipes).
  `initialize` advertises `mcpCapabilities.http/sse` accordingly.
- **LLM streaming**: OpenRouter via its OpenAI-compatible streaming API; text deltas are
  relayed immediately (no buffering), tool calls are merged from streamed deltas, and
  provider reasoning deltas (`delta.reasoning`) are relayed as `agent_thought_chunk`
  without being persisted to history. Every streamed `agent_message_chunk`,
  `agent_thought_chunk` and replayed chunk carries a per-content-block `messageId`
  (UUID, minted fresh per LLM iteration and shared by all deltas of one
  block within that iteration) so clients group deltas into one message
  instead of one bubble per delta; the UUID format follows the ACP
  message-id contract (`MessageId` doc: clients and agents MUST use UUID
  format) and the IntelliJ client groups thought deltas by it. The e2e pins
  this at the decoded-object AND raw-wire level (per-iteration distinctness,
  UUID format). Every request carries app attribution headers (`HTTP-Referer` +
  `X-OpenRouter-Title`, see openrouter.ai/docs/app-attribution).
- **Auto provider routing**: by default (`OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED=0`
  disables) every chat request carries a `provider` object: `sort: "throughput"` plus
  `max_price.completion` = the median completion price (USD per million tokens, standard
  median, all provider endpoints of the selected model via
  `GET /models/{author}/{slug}/endpoints`, `providerrouting/ProviderRouting.kt`, lazy
  per-model cache). Fail-open: a fetch/parse failure or an empty endpoints feed omits the
  `provider` field entirely so transient errors never break a turn; disabled routing
  never fetches endpoints.
- **Prompt capabilities + multimodal prompts**: `initialize` advertises
  `promptCapabilities` (`image` + `embeddedContext`, no `audio`). Prompt content blocks
  are converted per session model (`contentBlocksToLlmContentTopLevel` in
  `agent/AgentSessionImpl.kt`): plain text stays a flat string; an `image` block becomes
  a base64 `image_url` data URI (mime default `image/png`) when the selected model's
  `architecture.input_modalities` lists image, otherwise it degrades to a text
  placeholder; text `resource` blocks are inlined as `Resource <uri>:\n<text>`, blob
  resources and `audio` degrade to placeholders, and `resource_link` renders as a
  markdown link.
- **Project instructions (AGENTS.md)**: `<session cwd>/AGENTS.md` is read directly from
  disk on every prompt turn and injected into the system prompt as a
  `## Project Instructions (from AGENTS.md)` section (re-read per turn, so mid-session
  edits apply); a missing file is silently ignored, other read errors are logged and
  never fail the turn (`agent/AgentsMd.kt`).
- **`$/cancel_request`**: the SDK's `Protocol` handles both directions natively (an
  inbound `$/cancel_request` cancels the pending incoming request; cancellation of an
  outbound request auto-sends `$/cancel_request` and waits briefly for a graceful
  CANCELLED response). A cancelled `session/request_permission` therefore dismisses the
  client's permission prompt. `AgentSessionImpl.shouldAllow` rethrows
  `CancellationException` instead of swallowing it into a bogus "Permission denied".
- **Docker sandbox**: the agent image is CI-built (`.github/workflows/build-image.yml`,
  on push to `main` and manual dispatch) and published to
  `ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest` (linux/amd64; the 5 newest
  non-`latest` versions are kept, older ones pruned via `gh api` in the same workflow).
  The multi-stage `Dockerfile` builds the agent in a pinned `eclipse-temurin:25-jdk`
  stage (`installDist`; a `/root/.gradle` BuildKit cache mount keeps rebuilds
  incremental and `--no-daemon` avoids a lingering daemon). `installDist` copies
  Gradle-cache jars verbatim with mode 600 (root-owned in the builder), so the
  builder `chmod`s the install dir world-readable before the `COPY` (a `doLast` hook
  on `installDist` in `build.gradle.kts` normalizes perms at the source) - the
  non-root runtime user must be able to read the jars. It then copies it into the
  generic toolchain base `ghcr.io/dontdrinkandroot/dev:latest` (user `dev`, OpenJDK 25,
  XDG env, full toolchain for the agent's `bash` tool; the git `[user]` identity
  fallback goes to `/etc/gitconfig` because the tmpfs home shadows the image). The
  `ddr-acp-agent-docker` launcher runs the agent in a hardened container: pulls
  `:latest` at launch (stderr; falls back to a local image or hints at `./build-docker`),
  `--skip-pull` forces the local image; `development` docker network by default,
  non-root user matching host UID/GID, `--cap-drop=ALL` + `no-new-privileges`,
  read-only rootfs (relax with `ACP_DOCKER_RW_ROOTFS=1`), tmpfs `/tmp` and
  `/home/dev` (`ACP_DOCKER_HOME_VOLUME` switches the home to a named volume); host
  tool caches (uv, pip, composer, yarn, huggingface, npm, gradle, maven, cargo, go,
  nuget, pub, pnpm) shared in so builds reuse downloads (`ACP_DOCKER_MOUNT_CACHES=0`
  disables); the host session state dir (`$XDG_STATE_HOME/ddr-acp-agent`, default
  `~/.local/state/ddr-acp-agent`) is always shared rw so sessions survive the
  ephemeral tmpfs home; `OPENROUTER_*` + `FS_PROXY_ENABLED` are forwarded as env-only;
  a host `.env.local` is masked; host git identity is forwarded as env. Extras:
  `ACP_DOCKER_NETWORK`, `ACP_DOCKER_CAP_ADD`, `ACP_DOCKER_EXTRA_ARGS`, `DOCKER_BIN`;
  build locally with `./build-docker` (tags the same name). Launcher output is
  stderr-only — the ACP protocol travels over the container stdin/stdout.

## Layout

```
src/main/kotlin/net/dontdrinkandroot/acpagent/
    Main.kt                          # `main` (logging setup) + `runAgent`: registry, session
                                     # factory (AgentSessionFactory: create/restore), store
                                     # wiring, transport, isoDateToday
    BuildInfo.kt                     # build commit hash from git.properties (classpath)
    config/Config.kt                 # env config (OPENROUTER_*, FS_PROXY_ENABLED)
    config/PlatformEnv.kt            # platformEnv(): System.getenv() env source for config
    llm/LlmClient.kt                 # LLM transport only (HTTP/SSE/JSON)
    llm/LlmWire.kt                   # the single shared `llmWireJson` (snake-case etc.) for all
                                     # wire-shaped LLM data (chat traffic + persisted history)
    llm/LlmModels.kt                 # `GET /models` wire types (models feed, reasoning capability,
                                     # provider endpoints feed)
    providerrouting/ProviderRouting.kt  # auto provider routing: throughput sort + median completion cap
    mcp/McpBridge.kt                 # MCP ServerConnection, McpTool, JsonObject->Any map
    mcp/McpConnector.kt              # stdio/HTTP/SSE connect helpers (JVM)
    agent/SessionRecord.kt           # durable per-session state (history, mode, title, updatedAt)
    agent/SessionStore.kt            # atomic save/load/list/delete + isValidSessionId path guard
    agent/AgentSessionImpl.kt        # the agent loop: system prompt, mode/config, tool calls,
                                     # permission flow, persist/delete coordination, load replay
    agent/AgentsMd.kt                # AGENTS.md loader + instructions section for the system prompt
    agent/AgentSupportImpl.kt        # AgentSupport impl (initialize, create/load/resume/list/delete
                                     # sessions, AgentSessionFactory interface, randomSessionId)
    tools/Tool.kt                    # AgentTool, ToolContext, ToolResult
    tools/ToolRegistry.kt            # tool registry + mode filtering (availableForMode/disabledInMode)
    tools/BashTool.kt                # bash tool (ProcessBuilder)
    tools/Containment.kt             # isWithin / resolveAgainstSessionCwd (symlink-safe containment)
    tools/FileStore.kt               # FileStore interface, LocalFileStore, ClientFileStore (fs proxy)
    tools/PlanTool.kt                # UpdatePlanTool (emits ACP PlanUpdate, stores plan on session)
    tools/ReadFileTool.kt            # read_file tool (via FileStore)
    tools/WriteFileTool.kt           # write_file tool (via FileStore)
    tools/EditFileTool.kt            # edit_file tool (via FileStore)
    tools/ListDirTool.kt             # list_dir tool (local disk)
    tools/GlobTool.kt                # glob tool + internal walk/globToRegex helpers (local disk)
    tools/GrepTool.kt                # grep tool (local disk)
src/test/kotlin/                              # unit tests + black-box E2eConformanceTest
Dockerfile                              # multi-stage image: temurin-25 builder -> dev base
ddr-acp-agent                           # direct launcher (no docker): auto-rebuilds when
                                        # sources are newer than the installDist binary, then
                                        # execs it; embedded Gradle stdout is redirected to stderr
ddr-acp-agent-docker                    # docker launcher: sandboxed `docker run` for the agent
build-docker                            # local image build script (tags
                                        # ghcr.io/dontdrinkandroot/acp-agent.kotlin:latest)
.github/workflows/build-image.yml        # CI: builds/pushes image to GHCR on push to main,
                                        # prunes all but the 5 newest versions
.dockerignore                           # build context exclusions (.git, build/, .gradle/)
```

## Tools

* **Web research**: use the `exa_web_search_exa` and `exa_web_fetch_exa` tools for research, validating information and
  looking things up when unsure — do not guess. Search first with `exa_web_search_exa`, then fetch the full page with
  `exa_web_fetch_exa` when highlights are insufficient. Verify API contracts, library versions, spec details and
  upstream behavior against primary sources before relying on them; cite the sources you checked in your summary.

## Testing

`./gradlew test` runs unit tests plus the **black-box e2e** harness (`E2eConformanceTest`), which drives the linked
`installDist` launcher as a separate OS process with the **real official ACP SDK client** and a local mock OpenRouter
server. It asserts the core wire flow (incl. model/reasoning switches, `update_plan`, the
chat request's `reasoning` effort and the `usage_update` indicator), capability
advertising (`promptCapabilities` image/embeddedContext + `mcpCapabilities` http/sse),
AGENTS.md injection and multimodal prompt conversion (image data URI + inlined
resource), the `$/cancel_request` dismissal of a stuck permission prompt, the auto
provider routing (`provider` object with median cap, fail-open on endpoints error,
disabled via env), the system prompt's `Agent build:` hash round-tripped from the
classpath `git.properties`, and path-aware permissions + the client fs proxy (in-project
read/write without a prompt, out-of-project read prompts, proxy disabled via
`FS_PROXY_ENABLED=0` falls back to the local store). Plus a **persistence scenario
across three agent restarts** (`session/list` ->
`session/load` with replay -> `session/resume` -> delete).
All existing scenarios must pass **unchanged**.
Docker: validate the launcher with `bash -n ddr-acp-agent-docker` + `shellcheck ddr-acp-agent-docker build-docker`;
build the image with `./build-docker` and smoke-test by piping an `initialize` request into
`OPENROUTER_API_KEY=... ./ddr-acp-agent-docker --skip-pull` (expects a JSON-RPC response on stdout).
Direct launcher: validate with `bash -n ddr-acp-agent` + `shellcheck ddr-acp-agent`; smoke-test the same
way via `./ddr-acp-agent` (keep stdin open briefly after the request - closing it immediately races
the transport teardown and swallows the response).

**Koog upgrade checklist**: on every Koog version bump, re-verify the three hand-rolled
surfaces against the new version (see the "No more Koog" bullet under Boundaries) and
update this file: (1) `GET /models` wire types - still `internal`? still without the
`reasoning` block? (2) chat request - does Koog's OpenRouter request model carry
`reasoning {effort}` now? (3) `agents-features-acp` - still batch-emits events, no
permission flow/modes/usage indicator/replay? The moment any of these closes, port that
surface to Koog.

**Definition of done**: a change is done when `./gradlew build` passes (compile + all
tests incl. the black-box e2e).

## Feature parity port (acp-agent.go -> acp-agent.kotlin)

Reference: `dontdrinkandroot/acp-agent.go`; implemented features are listed under **Features** above.


## Conventions

* We always adhere to Clean Code and SOLID principles. Keep in mind that this avoids unnecessary comments and rather
  uses speaking variable and function names.
* When fixing bugs add regression tests if reasonably possible.
* _meta: JsonElement?` is threaded through every ACP model type exactly as the SDK does.
* Secrets come from env vars only (`OPENROUTER_API_KEY`, ...); never commit or print them.
  *`build/` is generated output - never edit or commit it.

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

## Pitfalls

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
- **Stale e2e binary**: `E2eConformanceTest` drives the installed launcher, and `test`
  depends on `installDist`. Running a single test from the IDE against an old
  install validates stale sources - re-link (`installDist`) first.
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
