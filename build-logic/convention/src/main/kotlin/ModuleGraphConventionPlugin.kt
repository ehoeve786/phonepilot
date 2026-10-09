import app.pocketpilot.buildlogic.ModuleEdge
import app.pocketpilot.buildlogic.ModuleGraphRules
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Applied to the root project. Registers `checkModuleGraph`, which fails the build when a
 * declared project dependency breaks a rule in [ModuleGraphRules].
 */
abstract class ModuleGraphConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        check(target == target.rootProject) { "pocketpilot.module.graph must be applied to the root project" }
        val subprojects = target.subprojects
        target.tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
            group = "verification"
            description = "Checks project dependencies against the module rules in spec section 2."
            edges.set(
                target.provider {
                    subprojects.flatMap { project ->
                        project.configurations.flatMap { configuration ->
                            configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                                "${project.path}|${dependency.path}|${configuration.name}"
                            }
                        }
                    }
                },
            )
        }
    }
}

abstract class CheckModuleGraphTask : DefaultTask() {
    /** Edges encoded as `from|to|configuration` so the input stays configuration-cache friendly. */
    @get:Input
    abstract val edges: ListProperty<String>

    @TaskAction
    fun check() {
        val parsed =
            edges.get().map { encoded ->
                val (from, to, configuration) = encoded.split("|")
                ModuleEdge(from, to, configuration)
            }
        val violations = ModuleGraphRules.violations(parsed)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module graph rules broken:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("Module graph OK: ${parsed.size} project dependencies checked.")
    }
}
