plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.server.mcp)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.kotlinx.coroutines.test)
}
