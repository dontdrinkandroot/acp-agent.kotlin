# file-tools Specification

## Purpose

Defines the tools that read, search and mutate files in the session's working directory and
its trusted paths: path handling, output shape and caps, move and delete semantics, exclusion
enforcement and the change evidence they report.

## Requirements

### Requirement: Relative paths resolve against the session cwd
Every path-scoped tool SHALL resolve a relative path against the session's working directory
before any I/O or permission decision, so the file touched is the file the decision approved.

#### Scenario: A relative read targets the session directory
- **WHEN** `read_file` is called with a relative path
- **THEN** the file inside the session cwd is read
- **Pinned by** `ReadFileToolTest` (unit), `PermissionAndFileStoreTest` (unit)

### Requirement: File access uses the client proxy when the client offers it
`read_file`, `write_file` and `edit_file` SHALL use the client's filesystem proxy when the
client advertises read and write filesystem capabilities and `tools.fsProxy` is not disabled,
and SHALL otherwise use local disk. The proxy SHALL NOT be called when it is unavailable.

#### Scenario: Proxy in use
- **WHEN** the client advertises read and write fs capabilities
- **THEN** reads and writes go through the client's filesystem methods, so unsaved editor state is seen
- **Pinned by** `PermissionAndFileStoreTest` (unit), `E2eFileStoreTest` (e2e)

#### Scenario: Proxy disabled in configuration
- **WHEN** `tools.fsProxy` is false
- **THEN** the local filesystem is used even though the client offers the proxy
- **Pinned by** `E2eFileStoreTest` (e2e)

#### Scenario: Proxy capability absent
- **WHEN** the client advertises no filesystem capabilities
- **THEN** no client filesystem method is called and local disk is used
- **Pinned by** `E2eFileStoreTest` (e2e)

### Requirement: read_file renders numbered lines and pages explicitly
`read_file` SHALL render content as fixed-width 1-based `number│content` lines with one
stripped trailing line terminator, SHALL require a `limit` between 1 and 2000 lines, and SHALL
append a continue footer naming the shown range, the total and the next line when the window
does not cover the file.

#### Scenario: Numbered output
- **WHEN** a five-line file is read with a limit covering it
- **THEN** each line is prefixed with its number and the content is otherwise verbatim
- **Pinned by** `ReadFileToolTest` (unit)

#### Scenario: A missing limit is refused
- **WHEN** `limit` is absent or outside 1..2000
- **THEN** the call fails before any I/O with a message naming the requirement
- **Pinned by** `ReadFileToolTest` (unit)

#### Scenario: A truncated window says so
- **WHEN** the window is shorter than the file
- **THEN** the footer names the shown range, the total line count and the line to continue from
- **Pinned by** `ReadFileToolTest` (unit), `VisualLinesTest` (unit)

#### Scenario: An empty file is explicit
- **WHEN** the file or window has no lines
- **THEN** the result reads `(empty content)` instead of a phantom line
- **Pinned by** `ReadFileToolTest` (unit), `VisualLinesTest` (unit)

#### Scenario: A line past the end is an error
- **WHEN** `line` exceeds the file's line count
- **THEN** the call fails naming the requested line and the actual count
- **Pinned by** `ReadFileToolTest` (unit)

#### Scenario: An over-long line is clamped
- **WHEN** a line exceeds 2000 characters
- **THEN** the rendered line is truncated with a visible marker
- **Pinned by** `ReadFileToolTest` (unit), `VisualLinesTest` (unit)

#### Scenario: A non-regular file is refused
- **WHEN** the path names a FIFO or device
- **THEN** the call fails instead of blocking or streaming forever
- **Pinned by** `ReadFileToolTest` (unit), `FileStoreTest` (unit)

#### Scenario: A huge file is read windowed
- **WHEN** the file is larger than any size cap
- **THEN** the requested window is read without a size refusal
- **Pinned by** `ReadFileToolTest` (unit), `FileStoreTest` (unit)

### Requirement: Client proxy reads never claim a completeness they cannot know
A read window SHALL never infer completeness from the response shape. A response that ignores
the requested limit SHALL be resolved by re-reading the whole file and slicing the window
locally; an ambiguous window SHALL be settled by one probe past the window. A failing probe
SHALL be treated as truncated.

#### Scenario: A limit-ignoring client still pages correctly
- **WHEN** the client returns the whole file although a limit was requested
- **THEN** the window is sliced locally and the footer states the real total
- **Pinned by** `PermissionAndFileStoreTest` (unit), `ReadFileToolTest` (unit)

#### Scenario: No phantom footer at end of file
- **WHEN** the window ends exactly at the end of the file
- **THEN** no continue footer is rendered
- **Pinned by** `PermissionAndFileStoreTest` (unit), `ReadFileToolTest` (unit)

#### Scenario: A failed probe truncates conservatively
- **WHEN** the probe fails
- **THEN** the window is reported as truncated
- **Pinned by** `PermissionAndFileStoreTest` (unit)

### Requirement: edit_file applies a delta and both writes report the whole file
`edit_file` SHALL replace an exact substring that occurs exactly once and SHALL fail
otherwise. A completed write or edit SHALL report the change as a whole-file diff when the
change is not rendered by the client proxy, and SHALL report a new file as having no old
content only when the file is genuinely absent.

#### Scenario: A non-unique match fails
- **WHEN** the search string occurs more than once or not at all
- **THEN** the call fails and the file is untouched
- **Pinned by** `EditFileToolTest` (unit)

#### Scenario: A whole-file diff
- **WHEN** the edit is applied locally
- **THEN** the diff carries the complete old and new content
- **Pinned by** `EditFileToolTest` (unit), `WriteFileToolTest` (unit)

#### Scenario: An existing but unreadable file is not reported as new
- **WHEN** the old content of an existing file cannot be read
- **THEN** the diff is omitted with a note instead of claiming an empty old text
- **Pinned by** `WriteFileToolTest` (unit)

### Requirement: Listing and searching bound their results and hide excluded files
`list_dir` and `glob` SHALL list at most 500 entries with an omission suffix. `grep` SHALL
cap matches at 500 while collecting, truncate each matched line at 500 characters and report
the number of skipped binary or excluded files.

#### Scenario: Listing cap
- **WHEN** a directory holds more than 500 entries
- **THEN** 500 are listed and a suffix states how many are omitted
- **Pinned by** `ListDirToolTest` (unit)

#### Scenario: Search cap
- **WHEN** a pattern matches more than 500 lines
- **THEN** the smallest 500 by path and line are reported with a truncation note
- **Pinned by** `GrepToolTest` (unit)

#### Scenario: Binary files are skipped and counted
- **WHEN** the search walks a binary file
- **THEN** no matches are reported from it and the skip note counts it
- **Pinned by** `GrepToolTest` (unit)

#### Scenario: Excluded files are skipped and counted
- **WHEN** the search walks an excluded file
- **THEN** its content is not searched and the skip note counts it
- **Pinned by** `GrepToolTest` (unit), `E2eExcludedFilesTest` (e2e)

### Requirement: grep accepts a single file as its path, glob does not
`grep` SHALL accept a single file as the search path and search only that file; `glob` SHALL
refuse a file as the root.

#### Scenario: Single-file grep
- **WHEN** `grep` names a file
- **THEN** only that file is searched
- **Pinned by** `GrepToolTest` (unit)

#### Scenario: glob refuses a file root
- **WHEN** `glob` names a file as its root
- **THEN** the call fails
- **Pinned by** `GlobToolTest` (unit)

#### Scenario: A symlinked single file is refused for grep
- **WHEN** the named path is a symlink
- **THEN** `grep` refuses instead of following it
- **Pinned by** `GrepToolTest` (unit)

### Requirement: An unusable search root fails loudly
`list_dir`, `glob` and `grep` SHALL fail with a named message when the root does not exist, is
not a directory or is not readable, instead of returning an empty result.

#### Scenario: Missing root
- **WHEN** the path does not exist
- **THEN** the call fails with `Path not found`
- **Pinned by** `ListDirToolTest` (unit), `GlobToolTest` (unit), `GrepToolTest` (unit)

#### Scenario: File as directory root
- **WHEN** `list_dir` names a file
- **THEN** the call fails with `Not a directory`
- **Pinned by** `ListDirToolTest` (unit), `GlobToolTest` (unit)

#### Scenario: Unreadable root
- **WHEN** the root directory cannot be listed
- **THEN** the call fails with `Not readable`
- **Pinned by** `ListDirToolTest` (unit)

### Requirement: Walks never follow discovered symlinks or enter .git
Directory walks SHALL skip symlinks and `.git` directories encountered inside the walk. A
named root MAY resolve through a symlink, because the permission layer decides whether the
real location is approved, and `.git` content SHALL still be covered by the symlink scan and
the recursive delete.

#### Scenario: A symlink inside the tree is not followed
- **WHEN** the walked directory contains a symlink to outside the project
- **THEN** the walk does not descend into or read through it
- **Pinned by** `GrepToolTest` (unit), `PermissionAndFileStoreTest` (unit)

#### Scenario: .git is not searched
- **WHEN** `grep` walks a project with a `.git` directory
- **THEN** no match is reported from inside `.git`
- **Pinned by** `GrepToolTest` (unit)

#### Scenario: A named root may be a symlink
- **WHEN** the search root itself is a symlink into an approved directory
- **THEN** the search follows the named root
- **Pinned by** `PermissionAndFileStoreTest` (unit)

### Requirement: move_file renames without overwriting
`move_file` SHALL fail when the destination exists, SHALL create missing destination parents,
and SHALL report the change as a delete plus add pair for every moved file.

#### Scenario: An existing destination is refused
- **WHEN** the destination already exists
- **THEN** the call fails and neither file changes
- **Pinned by** `MoveFileToolTest` (unit)

#### Scenario: Missing parents are created
- **WHEN** the destination's parent directory does not exist
- **THEN** it is created and the file moved
- **Pinned by** `MoveFileToolTest` (unit)

#### Scenario: Rename evidence
- **WHEN** a file is moved
- **THEN** the result carries a removal diff for the source and an addition diff for the destination
- **Pinned by** `MoveFileToolTest` (unit), `MoveDirectoryToolTest` (unit)

### Requirement: Deleting a file refuses directories and symlinks
`delete_file` SHALL delete a regular file only, SHALL refuse a directory or a symlink, and
SHALL carry the removed content as its evidence.

#### Scenario: A directory is refused
- **WHEN** `delete_file` names a directory
- **THEN** the call fails and nothing is deleted
- **Pinned by** `DeleteFileToolTest` (unit)

#### Scenario: A symlink is refused
- **WHEN** `delete_file` names a symlink
- **THEN** the call fails
- **Pinned by** `DeleteFileToolTest` (unit)

#### Scenario: The removed content is reported
- **WHEN** a file is deleted
- **THEN** the result carries a removal diff with the file's content
- **Pinned by** `DeleteFileToolTest` (unit)

### Requirement: Deleting a directory is refused when it contains a symlink
`delete_directory` SHALL delete recursively and SHALL refuse the whole operation when the tree
contains any symlink, because a link could redirect the deletion outside the approved project.

#### Scenario: A symlink in the tree blocks the delete
- **WHEN** the directory contains a symlink anywhere in its tree
- **THEN** the call fails and nothing is deleted
- **Pinned by** `DeleteDirectoryToolTest` (unit)

#### Scenario: A clean tree is removed with evidence
- **WHEN** the directory contains only regular files and directories
- **THEN** the tree is removed and each file is reported as a removal diff
- **Pinned by** `DeleteDirectoryToolTest` (unit)

### Requirement: Excluded files are unreachable through the file tools
A file matching an exclusion rule SHALL be refused to `read_file`, `edit_file`,
`write_file` and `delete_file` before any I/O, and SHALL be refused as source and as
destination of `move_file` and `move_directory`. The refusal SHALL name the matched rule.

#### Scenario: A direct read is refused
- **WHEN** `read_file` names an excluded file
- **THEN** the call fails naming the rule, without reading or asking for permission
- **Pinned by** `FileAccessExclusionsTest` (unit), `E2eExcludedFilesTest` (e2e)

#### Scenario: A move is refused in both directions
- **WHEN** an excluded file is moved onto another name, or another file is moved onto it
- **THEN** both calls fail
- **Pinned by** `MoveFileToolTest` (unit), `FileAccessExclusionsTest` (unit)

#### Scenario: A symlink to an excluded file is refused
- **WHEN** an alias link resolves to an excluded file
- **THEN** the direct read is refused
- **Pinned by** `FileAccessExclusionsTest` (unit)

#### Scenario: The tree evidence omits excluded content
- **WHEN** a deleted directory contains an excluded file
- **THEN** the delete proceeds but that file's content is not reported, and the count note states the gap
- **Pinned by** `DeleteDirectoryToolTest` (unit)

### Requirement: Path arguments are decoded strictly
A tool argument that is present but explicitly null SHALL be refused with
`'<name>' must not be null`, an absent argument with `Missing '<name>'`, and a wrongly typed
one with `'<name>' must be <type>`.

#### Scenario: Explicit null is refused
- **WHEN** a string argument is sent as JSON null
- **THEN** the call fails with `'<name>' must not be null` and nothing is written or executed
- **Pinned by** `WriteFileToolTest` (unit), `ReadFileToolTest` (unit)

#### Scenario: Missing argument
- **WHEN** the argument key is absent
- **THEN** the call fails with `Missing '<name>'`
- **Pinned by** `ReadFileToolTest` (unit)

### Requirement: Line-based reads share one line-counting convention
`read_file`, `grep` match numbers and paged `web_fetch` output SHALL count lines by one
convention: a trailing newline terminates the last line, a blank line between terminators is
real, and an empty file has zero lines.

#### Scenario: Trailing newline is not an extra line
- **WHEN** a file ends with a newline
- **THEN** the reported line count does not include a phantom empty last line
- **Pinned by** `VisualLinesTest` (unit), `ReadFileToolTest` (unit)

#### Scenario: A single blank line is one line
- **WHEN** a file consists of one newline character
- **THEN** it has one blank line
- **Pinned by** `VisualLinesTest` (unit)
