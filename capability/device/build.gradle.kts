plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.capability.device"
}

dependencies {
    implementation(projects.capability.api)
    implementation(libs.kotlinx.coroutines.core)
}
