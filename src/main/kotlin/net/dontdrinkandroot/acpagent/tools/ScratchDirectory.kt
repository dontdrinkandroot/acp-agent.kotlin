package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/**
 * Thrown when the scratch directory cannot be prepared. Session assembly turns
 * this into a refused session: a scratch directory that cannot be created or
 * verified must never be trusted, and a silently un-trusted one would only
 * surface later as an unexplained permission prompt.
 *
 * The refusal reaches the client as an internal error of `session/new`
 * (`-32603` via the SDK), carrying this message - it is deliberately not a
 * friendly "please fix your config" dialog.
 */
internal class ScratchDirectoryException(message: String) : Exception(message)

/**
 * Prepares the agent's scratch directory (`DDR_ACP_AGENT_SCRATCH_DIR`,
 * default `/tmp/ddr-acp-agent`) for the session and returns its
 * symlink-resolved path.
 *
 * The directory is a *read-trust root*, so it is verified rather than assumed:
 * [isWithinAnyRoot] resolves the root through symlinks, so a pre-created
 * symlink (the temp directory is world-writable) would silently widen the
 * trust domain to its target. Therefore:
 *
 * - a symlink, a non-directory, or a directory owned by another user is
 *   refused loudly instead of trusted;
 * - a directory we own with a wider mode is repaired to `0700` (mode drift from
 *   an earlier run is fixable without weakening the guarantee);
 * - a missing directory is created with `0700`, but only as a single level
 *   below an existing directory: creating intermediate parents would
 *   materialize directories nobody validated (and leave them with the process
 *   umask), so a missing parent is refused instead.
 *
 * The directory is never cleaned up - it is scratch space shared by every
 * session of this agent.
 *
 * Requires a POSIX filesystem (the `0700` requirement and the ownership check
 * are POSIX concepts): elsewhere the preparation fails loudly rather than
 * trusting a directory it cannot verify.
 */
internal fun prepareScratchDirectory(configured: String): String {
    val dir = Path.of(configured)
    val ownerOnly = PosixFilePermissions.fromString("rwx------")
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        val parent = dir.parent ?: throw ScratchDirectoryException("scratch directory '$dir' has no parent")
        if (!Files.isDirectory(parent)) {
            throw ScratchDirectoryException(
                "cannot create scratch directory '$dir': parent '$parent' does not exist or is not a directory"
            )
        }
        try {
            // The mode is set as a creation attribute, so the directory never
            // exists with the process umask even briefly.
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(ownerOnly))
        } catch (e: Exception) {
            throw ScratchDirectoryException(
                "cannot create scratch directory '$dir': ${e.message ?: e.javaClass.simpleName}"
            )
        }
        return realPathOf(dir)
    }
    if (Files.isSymbolicLink(dir)) {
        throw ScratchDirectoryException("scratch directory '$dir' is a symbolic link; refusing to trust its target")
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        throw ScratchDirectoryException("scratch directory '$dir' is not a directory")
    }
    val owner = runCatching { Files.getOwner(dir) }.getOrNull()
        ?: throw ScratchDirectoryException("cannot determine the owner of scratch directory '$dir'")
    if (owner != currentUserPrincipal()) {
        throw ScratchDirectoryException(
            "scratch directory '$dir' is owned by '$owner', not by the agent's user"
        )
    }
    runCatching { Files.setPosixFilePermissions(dir, ownerOnly) }
        .onFailure { failure ->
            throw ScratchDirectoryException(
                "scratch directory '$dir' cannot be restricted to 0700: " +
                        (failure.message ?: failure.javaClass.simpleName)
            )
        }
    return realPathOf(dir)
}

/**
 * The directory's symlink-resolved path, which is what the trust check needs:
 * [isWithinAnyRoot] resolves the *root* the same way, so handing it the real
 * path makes the trusted root explicit. Resolving here also covers a symlinked
 * *parent* (`/tmp/link/scratch` where `/tmp/link` points elsewhere), which the
 * leaf-only symlink check above cannot see.
 */
private fun realPathOf(dir: Path): String = runCatching { dir.toRealPath() }
    .getOrElse { failure ->
        throw ScratchDirectoryException(
            "cannot resolve the real path of scratch directory '$dir': " +
                    (failure.message ?: failure.javaClass.simpleName)
        )
    }
    .toString()

/**
 * The principal owning a file we create ourselves: a probe file in the default
 * temp directory is created and its owner read back. Comparing against
 * `user.name` would be unreliable (the property resolves to `?` in some
 * containers), and comparing principals needs no name mapping at all.
 *
 * An unprobeable principal is a refusal, not a skipped check: without it the
 * ownership of the trust root cannot be verified at all, and "verified or
 * refused" is the contract of the whole function.
 */
private fun currentUserPrincipal(): UserPrincipal = runCatching {
    val probe = Files.createTempFile("acp-owner-probe", null)
    try {
        Files.getOwner(probe)
    } finally {
        Files.deleteIfExists(probe)
    }
}.getOrElse { failure ->
    throw ScratchDirectoryException(
        "cannot determine the agent's own user principal to verify the scratch directory: " +
                (failure.message ?: failure.javaClass.simpleName)
    )
}
