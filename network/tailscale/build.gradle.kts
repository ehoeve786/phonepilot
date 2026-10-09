plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.network.tailscale"
    sourceSets.getByName("main").jniLibs.srcDir("build/gomobile/jni")
}

// The embedded Tailscale node is Go (tsnet), bound to Java with gomobile. Building it needs Go,
// gomobile and the Android NDK, which CI installs; the AAR stays confined to this module (spec
// section 2), unpacked into a classes jar and native libraries.
val goSources = layout.projectDirectory.dir("go")
val gomobileAar = layout.buildDirectory.file("gomobile/ppnet.aar")
val gomobileOut = layout.buildDirectory.dir("gomobile")

val gomobileBind =
    tasks.register<Exec>("gomobileBind") {
        description = "Builds the tsnet wrapper in go/ into an AAR with gomobile."
        inputs.files(fileTree(goSources) { include("**/*.go", "go.mod", "go.sum") })
        outputs.file(gomobileAar)
        workingDir(goSources)
        val out = gomobileAar.get().asFile
        doFirst { out.parentFile.mkdirs() }
        commandLine(
            "gomobile",
            "bind",
            "-target=android/arm64,android/amd64",
            "-androidapi",
            "30",
            "-javapkg",
            "app.pocketpilot.network.tailscale.gen",
            "-trimpath",
            "-ldflags=-s -w",
            "-o",
            out.absolutePath,
            ".",
        )
    }

val unpackGomobileAar =
    tasks.register<Copy>("unpackGomobileAar") {
        from(zipTree(gomobileAar).matching { include("classes.jar", "jni/**") })
        into(gomobileOut)
        dependsOn(gomobileBind)
    }

tasks.named("preBuild") { dependsOn(unpackGomobileAar) }

dependencies {
    api(projects.network.api)
    implementation(files(gomobileOut.map { it.file("classes.jar") }).builtBy(unpackGomobileAar))
    implementation(libs.kotlinx.coroutines.core)
}
