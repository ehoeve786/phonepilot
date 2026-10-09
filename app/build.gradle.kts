plugins {
    alias(libs.plugins.pocketpilot.android.application.compose)
    alias(libs.plugins.pocketpilot.hilt)
}

android {
    namespace = "app.pocketpilot"

    defaultConfig {
        applicationId = "app.pocketpilot"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // Duplicate metadata from the Ktor and MCP SDK jars.
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/io.netty.versions.properties",
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "/META-INF/DEPENDENCIES",
                )
        }
    }
}

dependencies {
    implementation(projects.capability.api)
    implementation(projects.capability.device)
    implementation(projects.core.audit)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.orchestrator)
    implementation(projects.core.tools)
    implementation(projects.server.http)
    implementation(projects.server.mcp)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
