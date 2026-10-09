plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.capability.shizuku"
    buildFeatures {
        aidl = true
    }
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.capability.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
