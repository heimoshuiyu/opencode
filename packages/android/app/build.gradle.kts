plugins {
    id("com.android.application") version "9.2.0"
}

abstract class BuildWeb : Exec() {
    @get:OutputDirectory abstract val assets: DirectoryProperty
}

abstract class PackageNode : Exec() {
    @get:OutputDirectory abstract val libs: DirectoryProperty
    @get:OutputDirectory abstract val notices: DirectoryProperty
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core:1.17.0") // FileProvider for a single temporary camera output URI.
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 36
    androidResources.noCompress += listOf("aac", "wav") // SoundPool opens asset file descriptors.
    defaultConfig {
        applicationId = "ai.opencode.android"
        testInstrumentationRunner = "ai.opencode.android.KeySmoke"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
    }
    sourceSets["main"].res.srcDir(rootProject.file("../desktop/icons/dev/android"))
    sourceSets["main"].res.srcDir(rootProject.file("third_party/notification-icons/res"))
    packaging.jniLibs {
        useLegacyPackaging = true // ProcessBuilder needs an installed, read-only executable path.
        keepDebugSymbols.add("**/liboc_*.so")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    val branch = providers.exec { commandLine("git", "rev-parse", "--abbrev-ref", "HEAD") }.standardOutput.asText
    val revision = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText
    val buildWeb = tasks.register<BuildWeb>("build${variant.name.replaceFirstChar { it.uppercase() }}Web") {
        workingDir(rootProject.file("../app"))
        // Vite follows imports throughout the monorepo, not just the app directory.
        outputs.upToDateWhen { false }
        doFirst {
            commandLine("bun", "run", "build", "--mode", "android", "--config", "../android/script/vite.config.ts",
                "--emptyOutDir", "--outDir", assets.dir("web").get().asFile)
        }
        doLast {
            assets.file("opencode-source.properties").get().asFile.writeText(
                "branch=${branch.get().trim()}\nbaseCommit=${revision.get().trim()}\nweb=packages/app\nserver=packages/cli\n"
            )
        }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(buildWeb, BuildWeb::assets)
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val runtime = tasks.register<PackageNode>("package${name}Node") {
        val script = rootProject.file("script/runtime.py")
        inputs.files(script, rootProject.file("script/runtime.lock"))
        doFirst { commandLine("python3", script, libs.get().asFile, notices.get().asFile) }
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(runtime, PackageNode::libs)
    variant.sources.assets?.addGeneratedSourceDirectory(runtime, PackageNode::notices)
    val usb = tasks.register<BuildWeb>("build${name}UsbReceiver") {
        val src = rootProject.file("script/usb-run.c")
        inputs.file(src)
        doFirst { commandLine("bash", "-eu", "-c", """
            bin="${'$'}{ANDROID_NDK_HOME:-${'$'}ANDROID_HOME/ndk/28.2.13676358}/toolchains/llvm/prebuilt/linux-x86_64/bin"
            for spec in arm64-v8a:aarch64 x86_64:x86_64; do
                dir="${'$'}2/${'$'}{spec%:*}"; mkdir -p "${'$'}dir"
                "${'$'}bin/${'$'}{spec#*:}-linux-android30-clang" -O2 -Wall -Werror -D_GNU_SOURCE -fPIE -pie -Wl,-z,max-page-size=16384 "${'$'}1" -o "${'$'}dir/liboc_usb_run.so"
            done
        """.trimIndent(), "build-usb", src, assets.get().asFile) }
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(usb, BuildWeb::assets)
    val audio = tasks.register<PackageNode>("package${name}Audio") {
        val script = rootProject.file("script/audio.sh")
        inputs.file(script)
        doFirst { commandLine("bash", script, libs.get().asFile, notices.get().asFile) }
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(audio, PackageNode::libs)
    variant.sources.assets?.addGeneratedSourceDirectory(audio, PackageNode::notices)
    val server = tasks.register<BuildWeb>("build${name}Server") {
        workingDir(rootProject.file("../cli"))
        outputs.upToDateWhen { false }
        doFirst { commandLine("bun", "run", "vite", "build", "--config", "../android/script/server.config.ts",
            "--outDir", assets.dir("runtime").get().asFile, "--emptyOutDir") }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(server, BuildWeb::assets)
}
