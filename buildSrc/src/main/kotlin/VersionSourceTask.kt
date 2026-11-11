import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the version a module is built as into a Kotlin source of its own: `internal const val <constant> = "<version>"`
 * in [packageName], so the module's code knows it without a resource or a BuildConfig class in its public API.
 */
abstract class VersionSourceTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val constant: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "build"
        description = "Writes the version the module is built as into a generated Kotlin source."
    }

    @TaskAction
    fun write() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(packageName.get().replace('.', '/')).apply { mkdirs() }
        dir.resolve("BuildVersion.kt").writeText(
            "package ${packageName.get()}\n\n/** The version this module was built as (Gradle's VERSION_NAME). */\n" +
                "internal const val ${constant.get()} = \"${version.get()}\"\n",
        )
    }
}
