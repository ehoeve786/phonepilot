plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.core.orchestrator)
    api(projects.capability.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
