import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Which modules may name a package: each rule maps a package to the directories (relative to the root) whose sources
 * may name it, in an import or written out in full; any other source that names it fails. A module directory that no
 * rule allows cannot reach the package even where its dependencies would let it compile.
 */
abstract class PackageRulesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** A package, and the directories whose sources may name it. */
    @get:Input
    abstract val allowedIn: MapProperty<String, List<String>>

    init {
        group = "verification"
        description = "Packages named only by the modules allowed to."
    }

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val rules = allowedIn.get().map { (pkg, dirs) -> Regex("""\b${Regex.escape(pkg)}\b(?!\w)""") to (pkg to dirs) }
        val problems = sources.files.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            problemsIn(file, path, rules)
        }
        if (problems.isNotEmpty()) throw GradleException("Package rules failed:\n" + problems.joinToString("\n"))
    }

    private fun problemsIn(file: File, path: String, rules: List<Pair<Regex, Pair<String, List<String>>>>): List<String> {
        val text = file.readText()
        return rules.mapNotNull { (pattern, rule) ->
            val (pkg, dirs) = rule
            val match = pattern.find(text) ?: return@mapNotNull null
            if (dirs.any { path.startsWith("$it/") }) return@mapNotNull null
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            val who = if (dirs.isEmpty()) "no module here may" else "only ${dirs.joinToString(", ")} may"
            "$path:$line: names $pkg, which $who"
        }
    }
}
