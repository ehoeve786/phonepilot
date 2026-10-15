plugins {
    alias(libs.plugins.pocketpilot.android.library.compose)
}

android {
    namespace = "app.pocketpilot.feature.clients"
}

dependencies {
    implementation(libs.androidx.compose.material3)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.compose.ui)
}
