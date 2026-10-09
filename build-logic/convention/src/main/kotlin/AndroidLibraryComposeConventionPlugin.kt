import app.pocketpilot.buildlogic.configureAndroidCompose
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

abstract class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "pocketpilot.android.library")
            configureAndroidCompose(extensions.getByType<LibraryExtension>())
        }
    }
}
