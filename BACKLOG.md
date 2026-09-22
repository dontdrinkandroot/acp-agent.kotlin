# Backlog

Ideas and future work that are out of scope right now, captured so they are
not lost.

## Plan mode may access /tmp

In the future, plan mode should also be able to access the temporary directory
(`/tmp`), e.g. to write scratch files while researching. This is intentionally
**not** implemented yet: it would widen the plan-mode read-only guarantee and
needs a decision on the scope (read-only access? the whole `/tmp`? a
session-scoped scratch subdirectory?) and the permission / containment rules
(`tools/Containment.kt`, `ToolCallExecutor`).

Out of scope for now; revisit when plan mode grows beyond pure read-only
research.

## Context compaction

The agent has **no compaction today**: history grows unbounded per session and
is persisted in full (`SessionRecord`). On a long task the window saturates and
the turn degrades (repeated suggestions, forgotten constraints) or the LLM call
starts failing. Compaction = **detect → summarize → replace → continue**: when
utilization crosses a threshold, older history is replaced by a model-written
summary while the recent tail stays verbatim.

Research findings from the other agents (2026-09, sources at the bottom):

- **Trigger**: everyone triggers on utilization vs. the model's context window.
  - Claude Code: a *budget*, not a percentage — `window − max(maxOutput, 20k)
    − 13k` reserve (≈83% of 200k); overrides (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`)
    can only fire *earlier*, never later than the default. Circuit breaker after
    3 consecutive compaction failures; a "thrashing" error when a huge tool
    output refills the window immediately after a compaction.
  - Gemini CLI: a fraction of the window (default 0.5, historically 0.7,
    user-configurable); skips the LLM summarization entirely after a failed
    attempt (truncation-only fallback).
  - Both avoid compacting tiny sessions.
- **What to summarize / keep**: split at a *safe boundary* — Gemini CLI splits
  at a `user` turn not inside a tool-call pair; the OpenAI wire format requires
  `tool_calls` to stay paired with their results, so a split must respect that
  too. Keep the recent tail verbatim (Gemini: newest ~30%, budget-capped
  tool outputs; Claude Code: recent exchanges plus rehydrated files).
- **Summary call**: one extra LLM request with a structured prompt.
  - Claude Code reuses the *identical request prefix* (system prompt, tools,
    full history) with the summarization instruction appended, so it reads the
    existing prompt cache. Output is a structured 9-section "working state"
    (intent, key decisions, files touched with line numbers, errors/fixes, all
    user messages verbatim, pending work, current state, next step), wrapped in
    a synthetic user message ("session continued from a previous conversation …
    resume without asking questions") plus *rehydration*: re-reads the ~5 most
    recently touched files and restores todo/plan state.
  - Gemini CLI asks for a `<state_snapshot>` and runs a **second verification
    turn** ("did you omit details?"); consecutive compactions *merge* into the
    previous snapshot instead of stacking.
  - Hard safety checks worth copying: reject the compaction when the new
    history is *bigger* than the original (Gemini
    `COMPRESSION_FAILED_INFLATED_TOKEN_COUNT`), and never run compaction inside
    the tool loop of the turn that triggered it (Claude Code does it between
    iterations, with a headroom reserve so the summarizer itself cannot
    overflow).
- **Model choice**: the industry default is the **same model** — Claude Code
  for prompt-cache friendliness, Gemini CLI maps the active model to the same
  tier of its family (`modelStringToModelConfigAlias`, pro→pro, flash→flash),
  Anthropic's server-side compaction is the model itself emitting a
  `compaction` block. Codex CLI also uses the session model today, but has open
  feature requests (openai/codex#13739, #22486) for a separate
  `compaction_model` + `reasoning_effort` with a fallback ladder (cheap → strong → session model); a small-model
  compaction can cost ~96%
  less. Cross-vendor quality risk: a summarizer from another vendor may mangle
  tool-call semantics — safe default is same model, downgrade only explicitly.
- **Client surface (ACP)**: RFD
  <https://agentclientprotocol.com/rfds/session-compaction> adds
  `compaction_update` + `compaction_summary_chunk` session updates: an
  ID-addressed lifecycle entity (`in_progress` → `completed`/`failed`/
  `cancelled`) with an optional user-displayable summary, replay-safe (replay the materialized form), gated behind a
  `clientCapabilities.session.compaction` advertisement. `usage_update` (which
  we already emit) reports utilization but is *not* a compaction boundary;
  ordinary agent messages are explicitly the wrong semantic shape.

How it would fit here (seams already exist):

1. **Trigger**: `PromptRunner.emitUsageUpdate` (`PromptRunner.kt`) already
   knows `used` vs. model `contextLength` — threshold check slots in there,
   before the next LLM iteration.
2. **Summarize**: a `ChatCompleter` call (the seam is unit-testable) with a
   structured summary prompt over the persisted `OpenAIMessage` history; all
   wire data round-trips through the shared `llmWireJson`.
3. **Replace**: rewrite `SessionState` history — summary as a synthetic
   user/system message + recent verbatim tail, respecting tool-call pairing —
   then persist via the existing record lifecycle and replay on
   `session/load`.
4. **Client surface**: emit `compaction_update` only when the client
   advertises the capability; otherwise fall back to an agent message (or
   silence).

Open decisions when this gets picked up: threshold value; separate compaction
model via env var (default = session model); whether microcompaction (dropping
stale tool results without an LLM call) is worth a first pass on its own; and
whether to adopt the ACP RFD now or wait for it to land in the SDK we pin.

Sources: Anthropic compaction docs (<https://platform.claude.com/docs/en/build-with-claude/compaction>), Claude
Code compaction deep-dives (threshold/9-section summary/rehydration, e.g.
<https://oldeucryptoboi.substack.com/p/context-compaction-deep-dive>), Gemini
CLI `chatCompressionService.ts` + `defaultModelConfigs.ts`, openai/codex#13739
and #22486, ACP session-compaction RFD (link above).
