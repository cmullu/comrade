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
 * The Compose surfaces Flutter replaces. Everything else under the legacy
 * source root is a service, a helper, or a pure function the services need.
 *
 * Filtered by a staging Sync rather than `sourceSets.filter.exclude` because
 * those patterns are matched relative to *every* source root — and
 * `mullu/comrade/MainActivity.kt` names both the Compose Activity we are
 * dropping and the Flutter one we are keeping.
 */
val composeOnlySources = listOf(
    "mullu/comrade/MainActivity.kt",
    "mullu/comrade/call/CallScreen.kt",
    "mullu/comrade/ui/AppIcons.kt",
    "mullu/comrade/ui/CallHistoryScreen.kt",
    "mullu/comrade/ui/ChatsScreen.kt",
    "mullu/comrade/ui/FeedScreen.kt",
    "mullu/comrade/ui/JournalScreen.kt",
    "mullu/comrade/ui/MediaAttachment.kt",
    "mullu/comrade/ui/OnboardingScreen.kt",
    "mullu/comrade/ui/SettingsScreen.kt",
    "mullu/comrade/ui/TaraScreen.kt",
    "mullu/comrade/ui/VoiceModelDownloadDialog.kt",
    "mullu/comrade/ui/theme/**",
)

val stagePreservedServices = tasks.register<Sync>("stagePreservedServices") {
    description = "Stages the preserved native services, minus the Compose UI Flutter replaces"
    from(legacyAndroid.resolve("java")) { exclude(composeOnlySources) }
    into(preservedSrcDir)
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

    testImplementation("junit:junit:4.13.2")
}

flutter {
    source = "../.."
}
