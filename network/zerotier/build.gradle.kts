plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

// ZeroTier through libzt, a userspace ZeroTier node and network stack in the app process. libzt
// publishes no Android package, so its pinned source (the libzt/ git submodule: libzt, the ZeroTier
// core and lwIP, all Apache-2.0 since their Business Source License change dates passed) is built
// here with the NDK, and its Java bindings are compiled into this module.
android {
    namespace = "app.pocketpilot.network.zerotier"

    defaultConfig {
        ndk {
            // The same ABIs as the Go networking library.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                targets += "zt-shared"
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("libzt/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory("libzt/src/bindings/java")
    }
}

dependencies {
    api(projects.network.api)
    implementation(projects.network.certificates)
    implementation(libs.kotlinx.coroutines.core)
}
