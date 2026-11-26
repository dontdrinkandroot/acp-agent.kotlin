package net.dontdrinkandroot.acpagent.sandbox

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * The fixture docker CLI (`src/test/resources/sandbox/fake-docker`) installed into a
 * directory on the fixture `PATH`: every invocation is recorded into a log file, `docker run`
 * consumes stdin and echoes it back. The tests hand it to the supervisor through `PATH`, like
 * a real host.
 */
class FakeDocker private constructor(val binary: Path) {

    companion object {
        fun install(directory: Path): FakeDocker {
            val binDirectory = directory.resolve("bin").createDirectories()
            val binary = binDirectory.resolve("docker")
            val resource = FakeDocker::class.java.getResourceAsStream("/sandbox/fake-docker")
                ?: error("fixture docker missing from test resources: /sandbox/fake-docker")
            resource.use { input -> binary.toFile().outputStream().use { input.copyTo(it) } }
            check(binary.toFile().setExecutable(true)) { "could not make the fixture docker executable" }
            return FakeDocker(binary)
        }

        /** One record per invocation: the argv tokens of that call, in order. */
        fun invocations(logFile: Path): List<List<String>> {
            if (!logFile.exists()) return emptyList()
            val lines = logFile.readLines()
            val records = mutableListOf<List<String>>()
            var index = 0
            while (index < lines.size) {
                val header = lines[index]
                require(header.startsWith("ARGV ")) { "malformed fake-docker log record: '$header'" }
                val tokenCount = header.removePrefix("ARGV ").toInt()
                index++
                require(index + tokenCount <= lines.size) { "truncated fake-docker log record at line $index" }
                records += lines.subList(index, index + tokenCount).map(::unescapeToken)
                index += tokenCount
            }
            return records
        }

        fun runInvocations(logFile: Path): List<List<String>> =
            invocations(logFile).filter { it.firstOrNull() == "run" }

        /** Reverses the fixture's token escaping (`\\` -> `\`, `\n` -> newline). */
        private fun unescapeToken(token: String): String {
            val result = StringBuilder(token.length)
            var index = 0
            while (index < token.length) {
                val char = token[index]
                if (char == '\\' && index + 1 < token.length) {
                    when (token[index + 1]) {
                        '\\' -> {
                            result.append('\\')
                            index += 2
                        }

                        'n' -> {
                            result.append('\n')
                            index += 2
                        }

                        else -> {
                            result.append(char)
                            index++
                        }
                    }
                } else {
                    result.append(char)
                    index++
                }
            }
            return result.toString()
        }
    }
}
