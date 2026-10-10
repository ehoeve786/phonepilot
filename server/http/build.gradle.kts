plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.server.mcp)
    api(projects.server.oauth)
    implementation(libs.ktor.server.cio)

    // The end-to-end test serves the real device.info tool backed by a fake source.
    testImplementation(projects.core.tools)
    testImplementation(libs.kotlinx.coroutines.test)
}
