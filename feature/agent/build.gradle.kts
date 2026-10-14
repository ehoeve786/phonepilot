plugins {
    alias(libs.plugins.pocketpilot.android.library.compose)
}

android {
    namespace = "app.pocketpilot.feature.agent"
}

dependencies {
    implementation(projects.agent.runtime)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
