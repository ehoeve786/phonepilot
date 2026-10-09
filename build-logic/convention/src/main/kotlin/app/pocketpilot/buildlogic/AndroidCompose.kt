package app.pocketpilot.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    apply(plugin = "org.jetbrains.kotlin.plugin.compose")
    commonExtension.buildFeatures.compose = true
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        "implementation"(platform(bom))
        "androidTestImplementation"(platform(bom))
        "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
        "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
    }
}
