package net.dontdrinkandroot.acpagent.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/*
 * The shared tool-diff emission policy (issue #58): one rule for every file
 * mutation, instead of an ad-hoc check per tool.
 *
 * A change the client fs proxy renders itself ([FileStore.rendersChange] -
 * write_file/edit_file written through the proxy) carries **no** tool diff:
 * the client snapshots and shows that change, and a diff would only duplicate
 * it and cost an extra `fs/read_text_file` round-trip ([emitsToolDiff] gates
 * the pre-read, not just the payload).
 *
 * Every **local-only** mutation - delete_file, delete_directory, move_file,
 * move_directory (ACP has no fs move/delete methods, so the proxy renders
 * nothing for them) - always carries its diff: the tool result is then the
 * only change evidence the client gets, which is what its rollback baseline
 * needs (issues #56/#58).
 *
 * Both sides of a diff are capped at [MAX_DIFF_CONTENT_LENGTH] so one oversized
 * file cannot blow up the wire payload ([toolResultDiff]); a dropped diff is
 * reported in the result text and on stderr, never silently (issue #57).
 */

/**
 * Upper bound for content payloads carried by [ToolResultDiff] so a
 * write/delete of an oversized file cannot blow up the wire payload.
 */
internal const val MAX_DIFF_CONTENT_LENGTH = 100_000

/** At most this many files of one tree mutation get diffs (issue #56). */
internal const val MAX_DIFF_FILES = 50

/**
 * Aggregate wire budget for one tool call's diffs (issue #56: the per-file and
 * per-call caps compose, so a 50-file tree cannot push 5 MB onto the wire).
 */
internal const val MAX_DIFF_TOTAL_LENGTH = 1 shl 20

/**
 * Whether a completed mutation must carry its own [ToolResultDiff]: false only
 * for a change the store already rendered (see the policy above). Checked
 * before any pre-read so the proxy path costs no round-trip.
 */
internal fun emitsToolDiff(changeRenderedByStore: Boolean): Boolean = !changeRenderedByStore

/**
 * What a completed mutation reports as its change evidence (issue #57: a
 * dropped diff is always surfaced - never silent).
 */
internal sealed interface DiffOutcome {
    /** The diff block to send for one changed file. */
    data class Emit(val diff: ToolResultDiff) : DiffOutcome

    /**
     * No diff could be produced; [reason] names what was lost (sizes, read
     * error) for the visible result-text note and the stderr warning.
     */
    data class Omit(val reason: String) : DiffOutcome

    /**
     * The change is rendered by the client fs proxy itself: nothing is lost,
     * so there is no diff and no note.
     */
    data object Suppressed : DiffOutcome
}

/**
 * Builds the change evidence for one changed file: the diff, or the reason it
 * was omitted. The size caps name the side, its size and the limit so the drop
 * is actionable (issue #57).
 */
internal fun toolResultDiff(path: String, newText: String, oldText: String?): DiffOutcome {
    if (newText.length > MAX_DIFF_CONTENT_LENGTH) {
        return DiffOutcome.Omit(
            "new content is ${formatBytes(newText.length.toLong())}, limit ${formatBytes(MAX_DIFF_CONTENT_LENGTH.toLong())}"
        )
    }
    if (oldText != null && oldText.length > MAX_DIFF_CONTENT_LENGTH) {
        return DiffOutcome.Omit(
            "original content is ${formatBytes(oldText.length.toLong())}, limit ${formatBytes(MAX_DIFF_CONTENT_LENGTH.toLong())}"
        )
    }
    return DiffOutcome.Emit(ToolResultDiff(path, newText, oldText))
}

/**
 * The visible note for an omitted diff (issue #57) plus the stderr warning
 * that keeps every drop visible when debugging - path and reason always, never
 * a silent skip. Returns the note for the result text.
 */
private fun reportOmittedDiff(path: String, reason: String): String {
    logOmittedDiff(path, reason)
    return "(diff omitted: $reason)"
}

/** The stderr half of [reportOmittedDiff], shared with the tree-diff collector. */
private fun logOmittedDiff(path: String, reason: String) {
    logger.warn { "diff omitted for $path: $reason" }
}

/**
 * The result of a completed single-file mutation: the base text plus, when the
 * diff was omitted, the visible `(diff omitted: <reason>)` note (issue #57).
 * [DiffOutcome.Suppressed] (the client fs proxy renders the change) keeps both
 * the text and the wire lean - nothing was lost there.
 */
internal fun mutationResult(base: String, path: String, outcome: DiffOutcome): ToolResult = when (outcome) {
    is DiffOutcome.Emit -> ToolResult(base, diffs = listOf(outcome.diff))
    is DiffOutcome.Omit -> ToolResult("$base ${reportOmittedDiff(path, outcome.reason)}")
    DiffOutcome.Suppressed -> ToolResult(base)
}

/**
 * The per-file change evidence of a tree mutation (issue #56), collected
 * within one bounded budget: at most [MAX_DIFF_FILES] files and
 * [MAX_DIFF_TOTAL_LENGTH] chars of wire payload per tool call, in sorted path
 * order (the reported prefix is deterministic). A file whose content is
 * unreadable, binary (NUL byte) or over [MAX_DIFF_CONTENT_LENGTH] is skipped -
 * warned and counted, never silently (issue #57); the caps themselves surface
 * through [partialNote]. Files matching the file-access exclusion policy
 * (`.env*.local`, `.ai/run.json`) are skipped without emitting content: the
 * tree mutation may remove them, but excluded content must never flow into
 * tool results - their bytes stay out of every diff block and the result text.
 */
internal class TreeDiffCollector(
    private val fileStore: FileStore,
    private val fileExclusions: FileAccessExclusions,
    private val cwd: String,
) {
    private val collected = mutableListOf<ToolResultDiff>()
    private var remainingBudget = MAX_DIFF_TOTAL_LENGTH

    /** Files seen in the tree - the `M of N` note denominator. */
    var totalFiles = 0
        private set

    /** Files whose change got diffs. */
    var reportedFiles = 0
        private set

    val diffs: List<ToolResultDiff> get() = collected

    /**
     * Evidences one file change: a removal diff at [path] and, when the file
     * moves instead of disappearing, an addition diff at [movedTo] (the v1
     * delete+add encoding of a rename). Reads the pre-change content through
     * the store - the user's view - like the single-file diff pre-reads.
     */
    suspend fun collect(path: String, movedTo: String? = null) {
        totalFiles++
        if (reportedFiles >= MAX_DIFF_FILES || remainingBudget <= 0) return
        if (fileExclusions.matchingRuleForTarget(cwd, path) != null) {
            logOmittedDiff(path, "matches a file-access exclusion rule")
            return
        }
        val content = try {
            fileStore.readRaw(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logOmittedDiff(path, "cannot read original: ${e.message ?: e}")
            return
        }
        if (content.contains('\u0000')) {
            logOmittedDiff(path, "binary content")
            return
        }
        if (content.length > MAX_DIFF_CONTENT_LENGTH) {
            logOmittedDiff(
                path,
                "original content is ${formatBytes(content.length.toLong())}, " +
                        "limit ${formatBytes(MAX_DIFF_CONTENT_LENGTH.toLong())}",
            )
            return
        }
        // A move duplicates the content on the wire (removal + addition).
        val cost = if (movedTo != null) 2 * content.length else content.length
        if (cost > remainingBudget) {
            remainingBudget = 0
            return
        }
        remainingBudget -= cost
        reportedFiles++
        collected += ToolResultDiff(path, "", content)
        if (movedTo != null) collected += ToolResultDiff(movedTo, content, null)
    }

    /**
     * The fact-based note for partial evidence (issue #57), null when every
     * file got its diff: `(original content reported for M of N files)`.
     */
    fun partialNote(): String? =
        if (reportedFiles < totalFiles) "(original content reported for $reportedFiles of $totalFiles files)" else null
}