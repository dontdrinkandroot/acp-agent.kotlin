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