pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "pocketpilot"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":capability:accessibility")
include(":capability:api")
include(":capability:apps")
include(":capability:device")
include(":capability:screencapture")
include(":capability:shizuku")
include(":core:audit")
include(":core:capabilities")
include(":core:common")
include(":core:imaging")
include(":core:model")
include(":core:orchestrator")
include(":core:policy")
include(":core:tools")
include(":feature:approvals")
include(":feature:clients")
include(":feature:network")
include(":network:api")
include(":network:certificates")
include(":network:gonet")
include(":network:public-access")
include(":network:tailscale")
include(":network:wireguard")
include(":network:zerotier")
include(":server:http")
include(":server:mcp")
include(":server:oauth")

// Closed Pro modules live in a private repository mounted at pro/ as a Git submodule.
// The public tree must build without it, so pro/ is only included when it is checked out.
if (file("pro/settings.pro.gradle.kts").exists()) {
    apply(from = "pro/settings.pro.gradle.kts")
}
