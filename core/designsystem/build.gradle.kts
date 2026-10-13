plugins {
    alias(libs.plugins.pocketpilot.android.library.compose)
}

android {
    namespace = "app.pocketpilot.core.designsystem"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
