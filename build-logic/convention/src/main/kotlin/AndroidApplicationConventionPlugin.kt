import app.pocketpilot.buildlogic.PocketPilotSdk
import app.pocketpilot.buildlogic.configureFlavors
import app.pocketpilot.buildlogic.configureKotlinAndroid
import app.pocketpilot.buildlogic.configureKtlint
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

abstract class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            configureKtlint()

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = PocketPilotSdk.TARGET
                configureFlavors()
                buildTypes.getByName("debug").applicationIdSuffix = ".debug"
                buildTypes.getByName("release").apply {
                    isMinifyEnabled = true
                    isShrinkResources = true
                }
                lint.apply {
                    warningsAsErrors = false
                    abortOnError = true
                    checkDependencies = true
                }
            }
        }
    }
}
