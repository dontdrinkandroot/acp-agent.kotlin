# ISSUES

Review findings from the 2026-09-22 code review. Each item cites the current code; all five
major bugs are verified against the sources and the tests that pin the surrounding behavior.

## Major bugs

### 1. Cancel during `bash`/`run` emits a spurious FAILED tool result

- `ProcessRunner.run` rethrows `CancellationException` after killing the process tree
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/BashTool.kt:141-147`), but
  `BashTool.execute` wraps the call in `runCatching`
  (`BashTool.kt:219`, failure conversion at `BashTool.kt:235`), which catches every
  `Throwable` — including cancellation — and turns it into `ToolResult("Command failed: …", true)`.
  Same defect in `RunTool.execute` (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/RunTool.kt:235-251`).
- Consequences: a `session/cancel` during a running command produces a bogus FAILED
  `tool_call_update` and a polluted history entry, and the turn keeps running until the next
  suspension point instead of ending as cancelled.
- `WebFetchTool` already handles this correctly by rethrowing cancellation
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/WebFetchTool.kt:49-53`);
  `ToolCallExecutor` also special-cases it (`ToolCallExecutor.kt:106-107`) — but the tools'
  inner `runCatching` swallows it first.
- Regression test: `E2eCancelTest` pins the permission-path equivalent ("no spurious permission-denied update
  expected").

### 2. `grep` accumulates every match in memory — OOM on large repos

- `GrepTool` collects all matches into `results`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/GrepTool.kt:43-75`), one `Triple`
  per matching line each holding a copy of the full line, sorts them, and only then applies
  the 500-match cap (`take(MAX_MATCHES)`, `GrepTool.kt:76-78`).
- A repo with many matching lines materializes every line in memory before truncation →
  GC pressure / OOM; the cap exists on paper but not in the accumulation path.
- Also: no early exit once the cap is reached, and the walker only skips `.git`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/GlobTool.kt:78`), so `build/`,
  `node_modules/` etc. are scanned on every call.

### 3. Permanent permissions keyed by tool name alone — one "Always allow" whitelists the tool session-wide

- `shouldAllow` stores `state.permanentPermissions[tool.name]`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/agent/ToolCallExecutor.kt:195-198`) and
  short-circuits on the bare tool name before any path check (`ToolCallExecutor.kt:163`).
- One "Always allow" on a single out-of-project `read_file` auto-approves every future
  `read_file` for the rest of the session — any path, any depth outside the project. Same
  for `bash`: one approval silences all subsequent command prompts. Symmetrically,
  "Always reject" bricks the tool for the session even for unrelated calls.
- The permission options carry no target context, and the decisions are not persisted (they evaporate on restart while
  mode/model persist). The real flaw is the session-scoped
  overgrant: the stated security model ("anything outside the cwd asks") degrades to
  "asked once".

### 4. Plan mode can execute arbitrary shell via `run`; `args` is a raw shell-injection channel

- `run` is `mutating = false` with no `modes`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/RunTool.kt:203-205`) and has no
  `targetPath`, so `permissionNeeded` returns false
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/agent/AgentSessionImpl.kt:298-312`) —
  prompt-free in every mode, including read-only plan.
- The command is executed via `/bin/sh` (`RunTool.kt:236`) and the model-supplied `args`
  are substituted for `{args}` with no escaping (`RunTool.kt:234`).
- Two parts exceed the documented trust tier ("config file is project-controlled"):
  (a) the read-only guarantee of plan mode rests entirely on the contents of `.ai/run.json`; (b) even a benign config
  like the shipped `test_class: timeout 60 ./gradlew test --tests "{args}"`
  becomes arbitrary command execution with a crafted `args` value (`x"; rm -rf … #`) —
  prompt-free, in plan mode.
- Fix direction: quote/escape `args` (or pass as positional parameters) and keep
  mutating-config executions gated.

### 5. Session/load replay renders denied and unknown/disabled tool calls as COMPLETED

- `replayHistory` maps every persisted `OpenAIMessage.Tool` to
  `SessionUpdate.ToolCallUpdate(status = COMPLETED, …)`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/agent/AgentSessionImpl.kt:181-187`).
- Denied calls append the same message shape with the denial text (`emitDenied`,
  `ToolCallExecutor.kt:131-152`), and unknown/disabled tools return before any
  `ToolCall` IN_PROGRESS is emitted (`ToolCallExecutor.kt:53-74`).
- After `session/load`: a permission-denied call shows as COMPLETED (it was FAILED live),
  and a mode-blocked/unknown call produces an orphan COMPLETED update for a `toolCallId`
  the client never saw introduced. `E2eSessionPersistenceTest` only exercises successful
  calls, so the gap is untested.

## Minor flaws

- `ClientFileStore.complete` mislabels the final window of a file *not* ending in a
  newline as truncated, so `read_file` emits a phantom
  "(Showing lines … of ?)" footer pointing past EOF
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/FileStore.kt:229-244`).
- A `content_filter` turn with no prior text never emits the explanatory chunk to the
  user: the emit is guarded on `partial != null`
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/agent/PromptRunner.kt:125`), yet the note
  is always appended to history.
- `LocalFileStore.readFile` scans the entire file even for a small window
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/FileStore.kt:60-84`).
- `resolvedPath`'s walk-up can go quadratic on deep not-yet-existing paths
  (`src/main/kotlin/net/dontdrinkandroot/acpagent/tools/Containment.kt:70-94`).
