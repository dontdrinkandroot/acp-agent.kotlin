package net.dontdrinkandroot.acpagent.sandbox

/**
 * A [HostProbes] answering from a declared fixture tree instead of the machine running the
 * tests: [fixtureDirectories] exist, [fixtureFiles] exist, and `createDirectories` succeeds
 * unless the fixture is deliberately read-only. That keeps the pinned `docker run`
 * composition independent of the host (and is what lets the table assert `/fixtures/...`
 * paths).
 */
class FixtureProbes(
    directories: List<String>,
    files: List<String>,
    private val creatable: Boolean = true,
) : HostProbes {

    private val directories = mutableSetOf<String>()
    private val files = mutableSetOf<String>()

    init {
        directories.forEach { addWithParents(it) }
        files.forEach { path ->
            parentOf(path)?.let { addWithParents(it) }
            this.files += path
        }
    }

    override fun isDirectory(path: String): Boolean = path in directories

    override fun exists(path: String): Boolean = path in directories || path in files

    override fun isRegularFile(path: String): Boolean = path in files

    override fun createDirectories(path: String): Boolean {
        if (!creatable) return false
        addWithParents(path)
        return true
    }

    override fun childDirectoryNames(parent: String): List<String> =
        directories
            .filter { parentOf(it) == parent }
            .map { it.substringAfterLast('/') }
            .sorted()

    private fun addWithParents(path: String) {
        var current: String? = path.trimEnd('/')
        while (current != null && current.isNotEmpty()) {
            if (!directories.add(current)) return
            current = parentOf(current)
        }
    }

    private fun parentOf(path: String): String? {
        val normalized = path.trimEnd('/')
        val index = normalized.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> null
            else -> normalized.substring(0, index)
        }
    }
}
