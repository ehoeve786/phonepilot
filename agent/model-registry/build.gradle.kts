plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.pocketpilot.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
