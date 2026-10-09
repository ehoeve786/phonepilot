plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.capability.api)
    implementation(libs.kotlinx.serialization.json)
}
