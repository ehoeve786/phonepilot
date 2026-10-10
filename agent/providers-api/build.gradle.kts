plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
