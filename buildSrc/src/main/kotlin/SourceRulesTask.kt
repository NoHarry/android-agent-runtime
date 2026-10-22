import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The repository's source rules: a file has at most [MAX_FILE_LINES] lines, a function spans at most
 * [MAX_FUNCTION_LINES], and nothing prints straight to stdout. A function is found the way the project always
 * measured it: a declaration line that opens a brace, through the line that closes it.
 */
abstract class SourceRulesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    init {
        group = "verification"
        description = "Files at most $MAX_FILE_LINES lines, functions at most $MAX_FUNCTION_LINES, no printing to stdout."
    }

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val problems = sources.files.sortedBy { it.path }.flatMap { problemsIn(it, it.relativeTo(root).path) }
        if (problems.isNotEmpty()) {
            throw GradleException("Source rules failed:\n" + problems.joinToString("\n"))
        }
    }

    private fun problemsIn(file: File, path: String): List<String> {
        val lines = file.readLines()
        val problems = mutableListOf<String>()
        if (lines.size > MAX_FILE_LINES) problems += "$path: ${lines.size} lines, above $MAX_FILE_LINES"
        lines.forEachIndexed { index, line ->
            if (STDOUT.containsMatchIn(line)) problems += "$path:${index + 1}: use a logger or trace event, not stdout"
        }
        functionsIn(lines, file.extension).forEach { (name, start, end) ->
            val span = end - start + 1
            if (span > MAX_FUNCTION_LINES) problems += "$path:$start: function $name spans $span lines, above $MAX_FUNCTION_LINES"
        }
        return problems
    }

    private fun functionsIn(lines: List<String>, extension: String): List<Triple<String, Int, Int>> {
        val patterns = if (extension == "java") JAVA_PATTERNS else KOTLIN_PATTERNS
        val functions = mutableListOf<Triple<String, Int, Int>>()
        lines.forEachIndexed { index, line ->
            val name = patterns.firstNotNullOfOrNull { it.find(line)?.groupValues?.get(1) }
            if (name == null || '{' !in line) return@forEachIndexed
            var depth = line.count { it == '{' } - line.count { it == '}' }
            var end = index + 1
            while (end < lines.size && depth > 0) {
                depth += lines[end].count { it == '{' } - lines[end].count { it == '}' }
                end++
            }
            functions += Triple(name, index + 1, end)
        }
        return functions
    }

    private companion object {
        const val MAX_FILE_LINES = 1000
        const val MAX_FUNCTION_LINES = 50
        val STDOUT = Regex("""System\.out\.println""")
        val KOTLIN_PATTERNS = listOf(
            Regex("""^\s*(?:public|private|protected|internal|open|abstract|final|sealed|companion|\s)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
            Regex(
                """^\s*(?:public|private|protected|internal|open|abstract|final|sealed|companion|\s)*""" +
                    """[A-Za-z_][A-Za-z0-9_<>\[\],\s?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{""",
            ),
        )
        val JAVA_PATTERNS = listOf(
            Regex(
                """^\s*(?:public|private|protected|static|final|abstract|synchronized|native|\s)+""" +
                    """[A-Za-z0-9_<>\[\],\s?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{""",
            ),
        )
    }
}
