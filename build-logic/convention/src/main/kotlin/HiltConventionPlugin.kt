import app.pocketpilot.buildlogic.lib
import app.pocketpilot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

abstract class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")
            dependencies {
                "ksp"(libs.lib("hilt-compiler"))
                "ksp"(libs.lib("kotlin-metadata"))
            }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                dependencies { "implementation"(libs.lib("hilt-core")) }
            }
            pluginManager.withPlugin("com.android.base") {
                apply(plugin = "com.google.dagger.hilt.android")
                dependencies { "implementation"(libs.lib("hilt-android")) }
            }
        }
    }
}
