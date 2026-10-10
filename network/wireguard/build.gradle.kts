plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.network.wireguard"
}

dependencies {
    api(projects.network.api)
    implementation(projects.network.certificates)
    implementation(libs.kotlinx.coroutines.core)
}
