plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.pocketpilot.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
