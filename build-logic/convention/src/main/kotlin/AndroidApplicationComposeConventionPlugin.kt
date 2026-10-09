import app.pocketpilot.buildlogic.configureAndroidCompose
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

abstract class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "pocketpilot.android.application")
            configureAndroidCompose(extensions.getByType<ApplicationExtension>())
        }
    }
}
