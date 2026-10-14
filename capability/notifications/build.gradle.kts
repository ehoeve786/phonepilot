plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.capability.notifications"
}

dependencies {
    api(projects.capability.api)
    implementation(libs.kotlinx.coroutines.core)
}
