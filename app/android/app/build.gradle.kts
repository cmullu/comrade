plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase 2 of the Flutter migration: build the preserved native services.
//
// The 6,955 LOC of non-UI Kotlin this phase keeps still live at
// android/app/src/main/java/mullu/comrade/**. Rather than move them (a
// 7,000-line diff that would drown the channel layer in review), this module
// stages them into build/ with the Compose UI filtered out, and compiles them
// alongside src/main/kotlin's new channel layer. Same package, so nothing in
// either half needed an import change.
//
// See src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md §0.
// ─────────────────────────────────────────────────────────────────────────────

/** Repo root: app/android → app → comrade. */
val comradeRoot = rootProject.projectDir.resolve("../..")
val legacyAndroid = comradeRoot.resolve("android/app/src/main")

val preservedSrcDir = layout.buildDirectory.dir("preserved/java")

/**
 * Which legacy sources are Compose UI that Flutter replaces — decided by
 * reading the file, not by a hand-kept list.
 *
 * This module has no Compose dependency, so staging one Compose file fails
 * `:app:compileDebugKotlin` outright. A literal list of filenames was the
 * obvious first cut and it broke within a day: `main` shipped the comrade-
 * presence feature, whose `ui/ComradesScreen.kt` was not on the list, and the
 * merge went red with 100+ `Unresolved reference 'compose'` errors. Any list
 * duplicated from another directory's contents goes stale the moment that
 * directory grows — the same failure mode as the `#[frb(mirror)]` drift the
 * bridge lane now guards against.
 *
 * An import scan is self-maintaining and, checked against the legacy tree at
 * the time of writing, exactly reproduces the hand-kept list plus the file it
 * had missed: 14 matches, no false positives, no false negatives. It is also
 * the honest definition of the thing being excluded — "a file that draws
 * Compose UI" is precisely "a file that imports androidx.compose".
 *
 * Filtered by a staging Sync rather than `sourceSets.filter.exclude` because
 * those patterns match relative to *every* source root — and
 * `mullu/comrade/MainActivity.kt` names both the Compose Activity being
 * dropped and the Flutter one being kept.
 */
val composeImport = Regex("""^\s*import\s+androidx\.compose""", RegexOption.MULTILINE)

val stagePreservedServices = tasks.register<Sync>("stagePreservedServices") {
    description = "Stages the preserved native services, minus the Compose UI Flutter replaces"
    val dropped = mutableListOf<String>()
    from(legacyAndroid.resolve("java")) {
        exclude { element ->
            val f = element.file
            val isCompose = !element.isDirectory &&
                f.name.endsWith(".kt") &&
                composeImport.containsMatchIn(f.readText())
            if (isCompose) dropped += element.path
            isCompose
        }
    }
    into(preservedSrcDir)
    // Printed so that a service accidentally importing Compose — which would
    // otherwise vanish silently and surface as a baffling "unresolved
    // reference" against its own class name — is visible in the build log.
    doLast {
        if (dropped.isNotEmpty()) {
            logger.lifecycle(
                "stagePreservedServices: dropped ${dropped.size} Compose source(s): " +
                    dropped.sorted().joinToString(", "),
            )
        }
    }
}

// ── uniffi-generated Kotlin bindings ─────────────────────────────────────────
//
// Unchanged in substance from android/app/build.gradle.kts:22-58 — the bindings
// are generated from the compiled cdylib's own embedded metadata (library
// mode), and are identical across variant and ABI, so a host build is enough to
// read them.
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/kotlin")
val hostCdylibName = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "libcomrade_jni.dylib"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "comrade_jni.dll"
    else -> "libcomrade_jni.so"
}
val hostCdylibPath = comradeRoot.resolve("target/debug/$hostCdylibName")

val cargoBuildHostCdylib = tasks.register<Exec>("cargoBuildHostCdylib") {
    description = "Builds comrade_jni for the host — only to read its uniffi interface metadata"
    workingDir = comradeRoot
    commandLine("cargo", "build", "-p", "comrade_jni")
    outputs.file(hostCdylibPath)
    outputs.upToDateWhen { hostCdylibPath.exists() }
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    description = "Generates Kotlin bindings from comrade_jni's uniffi interface"
    dependsOn(cargoBuildHostCdylib)
    workingDir = comradeRoot
    val outDir = uniffiOutDir.get().asFile
    doFirst { outDir.deleteRecursively() }
    commandLine(
        "cargo", "run", "-p", "comrade_uniffi_bindgen", "--",
        "generate",
        "--library", hostCdylibPath.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )
    inputs.file(hostCdylibPath)
    outputs.dir(uniffiOutDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiBindings, stagePreservedServices)
}

// ── The Rust core itself, cross-compiled for the shipped Android ABIs ────────
//
// The block above generates *bindings*; this one produces the thing they bind
// to. JNA resolves them against a real `libcomrade_jni.so` on first touch
// (`Native.register(…, "comrade_jni")` in the generated `uniffi/comrade/
// comrade.kt`), and `ComradeApplication.onCreate` warms exactly that touch on a
// background thread at process start — so an APK missing the .so does not lose
// a feature, it dies before the first frame.
//
// The legacy module never built it in Gradle at all: `.github/workflows/
// android-apk.yml` runs `cargo ndk` in a *separate CI job* and drops the result
// into `android/app/src/main/jniLibs/`, which is why a bare local
// `./gradlew assembleDebug` there also yields an APK that cannot start.
// Building it as a real Gradle task instead makes `flutter build apk` and
// `flutter run` sufficient on their own, with no CI-only step to keep in sync —
// and `.github/workflows/flutter.yml` already installs cargo-ndk and both Rust
// targets for precisely this, so nothing there needs to change.

/**
 * ABI → Rust target triple. Mirrors android-apk.yml's matrix, for the same two
 * reasons: arm64-v8a is what real handsets run (Pixel 9, Moto Edge 60 Pro),
 * x86_64 is what the emulator lanes run.
 *
 * Also the exact set `defaultConfig.ndk.abiFilters` pins below — see the note
 * there for why that pinning is load-bearing rather than an optimisation.
 */
val androidAbiTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

/**
 * Cross-compiles `comrade_jni` with `cargo ndk` into the `<abi>/lib*.so` layout
 * AGP wants from a jniLibs directory.
 *
 * A real typed task rather than the `Exec` the uniffi steps above use, because
 * this one's output has to be *wired into a variant*: `addGeneratedSourceDirectory`
 * takes a `DirectoryProperty`, and in exchange derives the task dependency
 * itself. That is what makes a from-scratch build work — the `kotlin.srcDir(…)`
 * + explicit `dependsOn` pattern used above for the generated bindings cannot
 * infer it (see the AGP 9 note in `sourceSets` below), and getting it wrong
 * here fails silently: the APK simply builds without the library.
 */
abstract class CargoNdkBuild : DefaultTask() {
    companion object {
        /** The cargo package built, and — as `lib<name>.so` — the file it produces. */
        const val CRATE = "comrade_jni"
    }

    /** ABI directory name → Rust target triple. */
    @get:Input
    abstract val abiTargets: MapProperty<String, String>

    /** Cargo features to enable. */
    @get:Input
    abstract val features: ListProperty<String>

    /**
     * NDK to build against, as a path string rather than an `@InputDirectory`:
     * fingerprinting a multi-gigabyte NDK on every build would cost more than
     * the build. The path embeds the version, so a version bump still
     * invalidates.
     */
    @get:Input
    abstract val ndkPath: Property<String>

    /** Cargo workspace root — `cargo ndk` runs here. */
    @get:Internal
    abstract val workspaceRoot: DirectoryProperty

    /** Everything that can change the .so: the crate sources and the lockfile. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection

    /** `<abi>/libcomrade_jni.so`. Convention set by `addGeneratedSourceDirectory`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:javax.inject.Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val ndk = File(ndkPath.get())
        // Fail with the fix rather than with cargo-ndk's "clang not found".
        if (!ndk.resolve("toolchains/llvm/prebuilt").isDirectory) {
            throw GradleException(
                "No usable Android NDK at '$ndk'. AGP resolves this from the module's " +
                    "`ndkVersion` (currently flutter.ndkVersion); install that NDK via " +
                    "sdkmanager, or point ANDROID_NDK_HOME at one and re-run.",
            )
        }

        val outDir = outputDirectory.get().asFile
        // Rebuilt from empty so that dropping an ABI from `androidAbiTargets`
        // cannot leave a stale .so behind for AGP to package.
        outDir.deleteRecursively()
        outDir.mkdirs()

        val abis = abiTargets.get()
        execOperations.exec {
            workingDir(workspaceRoot.get().asFile)
            // cargo-ndk locates its linker under $ANDROID_NDK_HOME. Neither it
            // nor ANDROID_HOME is exported in this repo's dev containers or on
            // the CI runners — the SDK reaches Gradle via local.properties'
            // `sdk.dir` — so pass AGP's own resolved NDK explicitly instead of
            // hoping the ambient environment has one.
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            environment("ANDROID_NDK_ROOT", ndk.absolutePath)
            commandLine(
                buildList {
                    add("cargo")
                    add("ndk")
                    abis.values.sorted().forEach {
                        add("--target")
                        add(it)
                    }
                    add("--output-dir")
                    add(outDir.absolutePath)
                    add("--")
                    add("build")
                    // Always release, never the Gradle variant's profile —
                    // matching android-apk.yml, which cross-compiles `--release`
                    // even for the debug APK its emulator lanes install. The
                    // workspace release profile (thin LTO, one codegen unit,
                    // stripped) is tuned specifically for this artifact because
                    // its size sets how long the warm-up blocks; the debug .so
                    // is ~270 MB against ~15 MB here, which is an APK you would
                    // not want to install even once.
                    add("--release")
                    add("-p")
                    add(CRATE)
                    features.get().takeIf { it.isNotEmpty() }?.let {
                        add("--features")
                        add(it.joinToString(","))
                    }
                },
            )
        }

        val soName = "lib$CRATE.so"
        abis.keys.forEach { abi ->
            val abiDir = outDir.resolve(abi)

            // `--output-dir` copies *every* cdylib the target directory holds,
            // and the graph contains one besides ours: `if-watch` (via libp2p)
            // also builds as a cdylib. Nothing loads it — libcomrade_jni.so's
            // only DT_NEEDEDs are liblog/libdl/libm/libc — so it is ~0.9 MB of
            // APK across both ABIs that no loader will ever open.
            abiDir.listFiles().orEmpty().forEach { if (it.name != soName) it.delete() }

            // An absent .so is precisely the defect this task exists to
            // prevent, and AGP packages an empty jniLibs directory without
            // complaint — so fail the build here rather than at first launch.
            if (!abiDir.resolve(soName).isFile) {
                throw GradleException("cargo ndk produced no $abi/$soName under $outDir")
            }
        }
    }
}

val androidComponentsExtension =
    extensions.getByType(com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java)

// AGP's own resolved NDK — honours the module's `ndkVersion` and will provision
// it if absent, so CI needs no separate NDK-install action.
val resolvedNdkPath = androidComponentsExtension.sdkComponents.ndkDirectory.map { it.asFile.absolutePath }

androidComponentsExtension.onVariants { variant ->
    // Registered per variant, not once and shared: `addGeneratedSourceDirectory`
    // sets the output directory's convention per variant, so one task wired into
    // two would silently have them share a directory. Per variant costs nothing
    // — only the variant actually being assembled is realised, and cargo no-ops
    // the second build anyway since the profile does not vary by variant.
    val cargoNdkBuild =
        tasks.register<CargoNdkBuild>(
            "cargoNdkBuild${variant.name.replaceFirstChar(Char::uppercase)}JniLibs",
        ) {
            description = "Cross-compiles comrade_jni for ${androidAbiTargets.keys.joinToString(", ")}"
            abiTargets.set(androidAbiTargets)
            // Matches android-apk.yml: enables the Blossom upload / fetch-and-
            // decrypt attachment pipeline, so the APK can actually send and
            // receive media. Off only in the lean workspace test build.
            features.set(listOf("media-http"))
            ndkPath.set(resolvedNdkPath)
            workspaceRoot.set(comradeRoot)
            rustSources.from(
                comradeRoot.resolve("Cargo.toml"),
                comradeRoot.resolve("Cargo.lock"),
                fileTree(comradeRoot.resolve("crates")),
            )
        }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDirectory)
}

android {
    namespace = "mullu.comrade"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "mullu.comrade"
        // 26, not flutter.minSdkVersion: the preserved services assume it
        // (STOP_FOREGROUND_REMOVE, notification channels, adaptive icons), and
        // the shipping Compose app already targets it.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // ── Default TURN relay, baked in from CI secrets ─────────────────────
        // Read by CallRelayDefaults. Empty when unset — local dev and fork PRs
        // still compile, and the app treats empty as "no baked-in default".
        //
        // SECURITY NOTE: these end up inside the APK and are extractable. They
        // are a convenience *default* relay only; a user-set relay in Settings
        // always overrides them.
        val turnField: (String?) -> String = { v ->
            "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        buildConfigField("String", "DEFAULT_TURN_URL", turnField(System.getenv("TURN_URL")))
        buildConfigField("String", "DEFAULT_TURN_USERNAME", turnField(System.getenv("TURN_USERNAME")))
        buildConfigField("String", "DEFAULT_TURN_PASSWORD", turnField(System.getenv("TURN_PASSWORD")))

        ndk {
            // Exactly the ABIs `androidAbiTargets` cross-compiles the Rust core
            // for — the pinning is what keeps those two lists from drifting.
            //
            // Left alone, Flutter defaults to armeabi-v7a + arm64-v8a + x86_64
            // and every dependency AAR (WebRTC, Vosk, JNA) supplies all three,
            // so the APK would ship a complete-looking armeabi-v7a slice that is
            // missing only libcomrade_jni.so — i.e. an APK that installs on a
            // 32-bit ARM device and then dies in the warm-up thread. That is the
            // precise failure being fixed here, so shipping a slice with it
            // would be self-defeating. Adding armv7 instead is possible but is a
            // third ABI to build, cache and ship, and neither android-apk.yml
            // nor flutter.yml has ever targeted it.
            //
            // Requires `disable-abi-filtering=true` in gradle.properties:
            // without it the Flutter plugin clears this set and re-adds all
            // three (FlutterPlugin.kt, `configureAbiWithoutSplits`).
            abiFilters.clear()
            abiFilters.addAll(androidAbiTargets.keys)
        }
    }

    buildFeatures {
        // Needed for the DEFAULT_TURN_* fields above.
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // Resolved to Files, not Providers: AGP 9 rejects Provider
            // instances in the SourceSet API. The task dependency Gradle would
            // otherwise infer is declared explicitly on KotlinCompile above.
            kotlin.srcDir(preservedSrcDir.get().asFile)
            kotlin.srcDir(uniffiOutDir.get().asFile)
            // Strings, the launcher foreground/colors, and the xml/ resources
            // the assist + recognition services and the FileProvider reference
            // from the manifest. No name collides with Flutter's own res/
            // (LaunchTheme/NormalTheme, the mipmap-*dpi PNGs).
            res.srcDir(legacyAndroid.resolve("res"))
        }
    }

    packaging {
        jniLibs {
            // Store .so files uncompressed so the linker mmaps them straight
            // from the archive — faster cold start for the multi-MB Rust core.
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            // TODO: real release signing, as android/app/build.gradle.kts does
            // from SIGNING_* env vars. Debug keys for now so `flutter run
            // --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // NotificationCompat/CallStyle, Person, ActivityCompat, ContextCompat,
    // FileProvider — used throughout Notifier, CallService and the channel layer.
    implementation("androidx.core:core-ktx:1.12.0")

    // Offline "Hey Comrade" wake word + speech recognition (Apache-2.0, no cloud)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Runtime support for the uniffi-generated bindings: JNA is how the
    // generated Kotlin calls into libcomrade_jni.so. `-android` is required
    // alongside `-core` for Dispatchers.Main, which the channel layer's event
    // relays and CallStateReactor both use.
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebRTC — voice/video call media. NOT `org.webrtc:google-webrtc` (JCenter
    // only, dead since 2021); `io.github.webrtc-sdk` is the maintained
    // Maven-Central successor and keeps the same `org.webrtc.*` namespace, so
    // every import in CallManager and the texture renderer compiles unchanged.
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Watch-together on a YouTube video (docs/TOGETHER.md §11b).
    //
    // **Here as well as in `android/app/build.gradle.kts`, and that is the
    // trap.** `stagePreservedServices` above copies every non-Compose Kotlin
    // file out of `android/` and compiles it here, so a third-party dependency
    // used by any preserved service has to be declared in *both* builds. Adding
    // it in one produces `Unresolved reference` against a package name in a file
    // nobody edited, in a build directory, which reads as anything but a missing
    // dependency.
    //
    // Same version as the Compose app deliberately: the staged sources are the
    // same sources, and two versions is one API difference away from this build
    // failing on code the other one compiles. (12.1.2 rather than 13.0.0 for the
    // reason that file gives — 13 needs compileSdk 35 and a Kotlin 2.x stdlib,
    // which `android/` does not have.)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.2")

    testImplementation("junit:junit:4.13.2")
}

flutter {
    source = "../.."
}
