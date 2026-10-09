import app.pocketpilot.buildlogic.PocketPilotSdk
import app.pocketpilot.buildlogic.configureKotlinAndroid
import app.pocketpilot.buildlogic.configureKtlint
import app.pocketpilot.buildlogic.lib
import app.pocketpilot.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            configureKtlint()

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = PocketPilotSdk.TARGET
                lint.targetSdk = PocketPilotSdk.TARGET
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                // Resources in :core:foo must be prefixed core_foo_ so modules never collide.
                resourcePrefix = path
                    .split(":")
                    .filter(String::isNotEmpty)
                    .joinToString("_")
                    .lowercase() + "_"
            }
            dependencies {
                "testImplementation"(libs.lib("kotlin-test-junit"))
                "testImplementation"(libs.lib("junit"))
            }
        }
    }
}
