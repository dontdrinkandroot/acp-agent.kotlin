package net.dontdrinkandroot.acpagent.tools

import java.util.Locale

/**
 * Renders a byte count for user-facing size messages ("4.2 MB", "31 KB",
 * "512 bytes"). Shared by the file-store size cap and the web-fetch body cap.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}