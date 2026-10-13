plugins {
    alias(libs.plugins.pocketpilot.android.library.compose)
}

android {
    namespace = "app.pocketpilot.feature.automations"
}

dependencies {
    api(projects.core.automation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
