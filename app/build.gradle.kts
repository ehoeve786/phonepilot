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

    // Every CI runner would otherwise make its own debug key, and Android refuses to update an app
    // signed with a different key. This key is public and signs debug builds only.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    implementation(projects.capability.accessibility)
    implementation(projects.capability.api)
    implementation(projects.capability.apps)
    implementation(projects.capability.device)
    implementation(projects.capability.screencapture)
    implementation(projects.capability.shizuku)
    implementation(projects.core.audit)
    implementation(projects.core.capabilities)
    implementation(projects.core.common)
    implementation(projects.core.imaging)
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
