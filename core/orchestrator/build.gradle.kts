plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(projects.core.audit)
    api(projects.core.model)
    implementation(projects.core.common)

    testImplementation(libs.kotlinx.coroutines.test)
}
