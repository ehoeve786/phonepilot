plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.core.orchestrator)
    api(libs.mcp.kotlin.sdk.server)

    testImplementation(libs.kotlinx.coroutines.test)
}
