plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.agent.providersApi)
    api(projects.core.orchestrator)
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
