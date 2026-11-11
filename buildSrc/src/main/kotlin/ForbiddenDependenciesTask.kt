import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * A module ([modulePath]) that must not carry certain modules of this build: none of [forbidden] (project paths) may be
 * on its classpaths, directly or through another module ([found], the projects its classpaths resolve to).
 */
abstract class ForbiddenDependenciesTask : DefaultTask() {
    /** The module checked, set when the task is configured: a task does not read its project while it runs. */
    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val forbidden: SetProperty<String>

    @get:Input
    abstract val found: SetProperty<String>

    init {
        group = "verification"
        description = "Modules this one must not carry, directly or through another."
    }

    @TaskAction
    fun check() {
        val carried = found.get().intersect(forbidden.get()).sorted()
        if (carried.isNotEmpty()) {
            throw GradleException("${modulePath.get()} must not carry ${carried.joinToString(", ")}, but its classpaths do.")
        }
    }
}

/** The project paths a resolved classpath reaches from [root], directly or through other components. */
fun projectsReachedFrom(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (!seen.add(component)) continue
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending += it.selected }
    }
    return seen.filter { it != root }.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.toSet()
}
