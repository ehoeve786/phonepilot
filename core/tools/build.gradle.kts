plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.pocketpilot.kotlin.serialization)
}

dependencies {
    api(projects.core.orchestrator)
    api(projects.capability.api)
    api(projects.core.imaging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
