plugins {
    alias(libs.plugins.pocketpilot.android.library.compose)
}

android {
    namespace = "app.pocketpilot.feature.network"
}

dependencies {
    implementation(projects.network.api)
    implementation(projects.network.certificates)
    implementation(projects.network.publicAccess)
    implementation(projects.network.wireguard)
    implementation(projects.network.zerotier)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
