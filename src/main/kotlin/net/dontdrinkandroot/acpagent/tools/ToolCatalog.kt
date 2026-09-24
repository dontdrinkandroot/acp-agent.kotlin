package net.dontdrinkandroot.acpagent.tools

/**
 * The local (cwd-independent) tools every session gets. This is the single
 * source of truth for the production registry assembly in `Main.kt` — the
 * schema tests iterate this list, so a tool added here without a schema pin is
 * caught by the registry-wide guard test.
 */
internal fun localTools(): List<AgentTool> = listOf(
    ReadFileTool(),
    WriteFileTool(),
    EditFileTool(),
    MoveFileTool(),
    MoveDirectoryTool(),
    DeleteFileTool(),
    DeleteDirectoryTool(),
    ListDirTool(),
    GlobTool(),
    GrepTool(),
    BashTool(),
    WebFetchTool(),
    UpdatePlanTool(),
    GetCurrentModeTool(),
)

/**
 * The cwd-dependent per-session tools. Like [localTools], iterated by the
 * schema tests over the true production list.
 */
internal fun sessionTools(cwd: String): List<AgentTool> = listOf(
    RunTool(cwd),
    ListRunConfigsTool(cwd),
    CreateRunConfigTool(cwd),
    UpdateRunConfigTool(cwd),
    DeleteRunConfigTool(cwd),
)