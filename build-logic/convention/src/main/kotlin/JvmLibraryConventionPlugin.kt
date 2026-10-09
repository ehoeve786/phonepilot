import app.pocketpilot.buildlogic.configureKotlinJvm
import app.pocketpilot.buildlogic.configureKtlint
import app.pocketpilot.buildlogic.lib
import app.pocketpilot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin modules with no Android dependency, such as core/model and core/common. */
abstract class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")
            configureKotlinJvm()
            configureKtlint()
            dependencies {
                "testImplementation"(libs.lib("kotlin-test"))
                "testImplementation"(libs.lib("junit"))
            }
        }
    }
}
