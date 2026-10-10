plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.network.certificates"
}

dependencies {
    api(projects.network.api)
    api(projects.network.gonet)
    implementation(libs.kotlinx.coroutines.core)
}
