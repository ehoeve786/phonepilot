plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
