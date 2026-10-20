plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.pocketpilot.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.orchestrator)
    implementation(projects.capability.api)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
