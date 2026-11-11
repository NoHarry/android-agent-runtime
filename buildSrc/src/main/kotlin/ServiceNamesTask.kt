import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Sources that may not name a model service or its models: no file of [sources] contains one of [names] (regular
 * expressions, matched ignoring case). What is known of a service belongs to its adapter, in a module of its own.
 */
abstract class ServiceNamesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    abstract val names: ListProperty<String>

    init {
        group = "verification"
        description = "Sources that name no model service and none of its models."
    }

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val pattern = Regex(names.get().joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE)
        val problems = sources.files.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                pattern.find(line)?.let { "$path:${index + 1}: names \"${it.value}\"" }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The app server's core names model services or their models, which only their adapters may:\n" +
                    problems.joinToString("\n"),
            )
        }
    }
}
