import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.firebase.appdistribution)
}

// Load Gemini API key from local.properties (never committed). Falls back to env vars for CI.
val geminiApiKey: String = run {
    val propsFile = rootProject.file("local.properties")
    val fromFile = if (propsFile.exists()) {
        Properties().apply { propsFile.inputStream().use { load(it) } }
            .getProperty("GEMINI_API_KEY")
            ?.takeIf { it.isNotBlank() }
    } else null
    fromFile ?: System.getenv("GEMINI_API_KEY") ?: ""
}

// Gemma model distribution config (priority-2 on-device LLM). URL, SHA-256, and
// byte size are loaded from local.properties or CI env vars — identical pattern
// to GEMINI_API_KEY. Empty values make the MediaPipe engine report Unavailable
// at runtime so the provider falls back to cloud; the build still succeeds.
// See android/SETUP.md §7 for how to host the model on Firebase Storage and
// compute the SHA-256.
private fun readLocalProperty(name: String): String {
    val propsFile = rootProject.file("local.properties")
    val fromFile = if (propsFile.exists()) {
        Properties().apply { propsFile.inputStream().use { load(it) } }
            .getProperty(name)
            ?.takeIf { it.isNotBlank() }
    } else null
    return fromFile ?: System.getenv(name) ?: ""
}
val gemmaModelUrl: String = readLocalProperty("GEMMA_MODEL_URL")
val gemmaModelSha256: String = readLocalProperty("GEMMA_MODEL_SHA256")
val gemmaModelSizeBytes: Long = readLocalProperty("GEMMA_MODEL_SIZE_BYTES").toLongOrNull() ?: 0L

// Debug signing: the keystore is restored from GitHub Secret DEBUG_KEYSTORE_B64 on CI
// and committed-out locally (*.keystore is .gitignore'd). Without a fixed keystore,
// every CI runner generates a fresh ~/.android/debug.keystore so the APK's signing
// cert changes between builds and Android refuses to upgrade installed APKs with
// INSTALL_FAILED_UPDATE_INCOMPATIBLE. See android/SETUP.md §3.2.
// Module-relative path (resolves to android/app/debug/corechat-debug.keystore). Using
// project.file keeps the intent "this module's debug/" obvious vs. rootProject.file
// which would silently break if the Gradle rootDir ever moved.
val debugKeystoreFile: File = project.file("debug/corechat-debug.keystore")
val debugStorePassword: String = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
val debugKeyAlias: String = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
val debugKeyPassword: String = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"

// Version SoT: android/version.properties. See that file for the rationale.
// versionCode is derived from GITHUB_RUN_NUMBER on CI to guarantee monotonic increase
// across builds; otherwise every Firebase-distributed APK would share versionCode=1
// and Android would refuse the install as "same version".
val versionProps: Properties = Properties().apply {
    val file = rootProject.file("version.properties")
    // Fail-fast: the file is the single Source of Truth for app version. A silent
    // default here would mask a missing/renamed file and ship builds with surprise
    // versionName/versionCode values.
    require(file.exists()) {
        "android/version.properties is missing — it is the SoT for app version. See android/SETUP.md §4.1."
    }
    file.inputStream().use { load(it) }
}
val resolvedVersionName: String =
    versionProps.getProperty("versionName")?.takeIf { it.isNotBlank() }
        ?: error("version.properties: 'versionName' must be set (e.g. versionName=0.1.0)")
val versionCodeOffset: Int = versionProps.getProperty("versionCodeOffset", "0").toIntOrNull()
    ?: error("version.properties: 'versionCodeOffset' must be a non-negative integer")
val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val resolvedVersionCode: Int = if (ciRunNumber > 0) {
    versionCodeOffset + ciRunNumber
} else {
    // Local / non-CI builds: offset + 1 so fresh installs still have versionCode >= 1.
    versionCodeOffset + 1
}
// Android/Play Store reject versionCode > 2_100_000_000. A misconfigured offset could
// silently produce an unshippable APK — catch that at configuration time.
require(resolvedVersionCode in 1..2_100_000_000) {
    "Resolved versionCode=$resolvedVersionCode is out of range (1..2_100_000_000). Check version.properties versionCodeOffset."
}

android {
    namespace = "com.tsunaguba.corechat"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            // fail-open: if the fixed keystore is missing (e.g. fresh clone before
            // SETUP.md §3.2 is followed), AGP falls back to auto-generating
            // ~/.android/debug.keystore so `./gradlew assembleDebug` still works.
            // On CI, the `Restore debug keystore from Secret` step writes this file
            // before Gradle runs, so the signing cert stays constant across runs.
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.tsunaguba.corechat"
        // AICore SDK requires minSdk 31 (Android 12). Runtime Gemini Nano support
        // additionally needs Android 14+ on select devices; the engine provider
        // handles runtime availability and falls back to cloud when unsupported.
        minSdk = 31
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey}\"")

        // Gemma model download metadata — consumed by GemmaModelDownloader at first
        // launch. Empty URL disables the MediaPipe engine cleanly (engine reports
        // Unavailable and the provider routes to cloud). SHA-256 is verified after
        // download to detect tampering or truncated transfers.
        buildConfigField("String", "GEMMA_MODEL_URL", "\"${gemmaModelUrl}\"")
        buildConfigField("String", "GEMMA_MODEL_SHA256", "\"${gemmaModelSha256}\"")
        buildConfigField("long", "GEMMA_MODEL_SIZE_BYTES", "${gemmaModelSizeBytes}L")
    }

    // Stamp the version + buildType into the APK filename so Firebase-distributed
    // downloads don't collide on disk ("corechat-v0.1.0-42-debug.apk" instead of
    // the old "app-debug.apk"). Without this, repeat downloads get "(1)", "(2)"
    // suffixes from the browser and testers can't tell which build they are running.
    //
    // Note: `variant.versionName` here is the BASE value from defaultConfig and
    // does NOT include `versionNameSuffix = "-debug"`. The suffix is applied to
    // the manifest at merge time; we rely on the explicit `buildType.name` segment
    // in the filename to disambiguate debug vs release APKs.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val impl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            impl.outputFileName =
                "corechat-v${variant.versionName}-${variant.versionCode}-${variant.buildType.name}.apk"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Intentionally no applicationIdSuffix: Firebase App Distribution is
            // registered against applicationId "com.tsunaguba.corechat". Using the
            // ".debug" suffix would produce a package name Firebase rejects on upload
            // ("package name does not match your Firebase app's package name").
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")

            // Diagnostics for the debug-only "AI利用不可" card. Release variants get
            // zeroed placeholders so the actual key length / fingerprint never ship in
            // consumer builds — even though the key body itself is still in BuildConfig
            // (see SETUP.md §3.1), there's no reason to expose additional metadata.
            // Use the top-level `MessageDigest` import — fully-qualified
            // `java.security.MessageDigest` resolves against the Gradle Kotlin DSL's
            // `java` project extension inside this scope and fails compilation.
            val geminiKeyHashPrefix: String = if (geminiApiKey.isEmpty()) {
                ""
            } else {
                MessageDigest.getInstance("SHA-256")
                    .digest(geminiApiKey.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
                    .substring(0, 12)
            }
            buildConfigField("int", "GEMINI_API_KEY_LENGTH", "${geminiApiKey.length}")
            buildConfigField("String", "GEMINI_API_KEY_SHA256_PREFIX", "\"$geminiKeyHashPrefix\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Zeroed diagnostics so the debug card fields exist for compile but carry
            // no information in release APKs.
            buildConfigField("int", "GEMINI_API_KEY_LENGTH", "0")
            buildConfigField("String", "GEMINI_API_KEY_SHA256_PREFIX", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

firebaseAppDistribution {
    artifactType = "APK"
    // Actual testers / groups are supplied by the CI workflow (wzieba action).
    // This Gradle block exists only for local `./gradlew appDistributionUploadDebug` usage.
    // Groups default to empty: local invocations distribute only to the explicit
    // testers list, if any. Override via -PfadGroups=group1,group2 only after
    // confirming those aliases exist in the Firebase console.
    groups = (project.findProperty("fadGroups") as String?) ?: ""
    testers = (project.findProperty("fadTesters") as String?) ?: ""
    releaseNotes = (project.findProperty("fadReleaseNotes") as String?) ?: "Local build"
}

// Local diagnostic: verify that the debug keystore at app/debug/corechat-debug.keystore
// matches the one CI signs with. Running this on two machines (or CI vs local) and
// comparing the SHA-1 output confirms whether testers will get "update" vs
// INSTALL_FAILED_UPDATE_INCOMPATIBLE. See android/SETUP.md §3.2.
tasks.register("printDebugCertFingerprint") {
    group = "verification"
    description = "Prints SHA-1 of the debug signing cert. Must match CI and across machines."
    doLast {
        require(debugKeystoreFile.exists()) {
            "Debug keystore missing at ${debugKeystoreFile.absolutePath}. See android/SETUP.md §3.2."
        }
        exec {
            commandLine(
                "keytool", "-list", "-v",
                "-keystore", debugKeystoreFile.absolutePath,
                "-storepass", debugStorePassword,
                "-alias", debugKeyAlias,
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // On-device Gemini Nano via AICore (primary engine)
    implementation(libs.google.ai.edge.aicore)
    // On-device Gemma via MediaPipe LiteRT-LM (secondary engine, used when AICore
    // is unavailable — e.g. Samsung devices that don't ship the Gemini Nano
    // feature ID even though the AICore APK is installed).
    implementation(libs.mediapipe.tasks.genai)
    // Cloud Gemini (tertiary fallback when both on-device engines are unavailable)
    implementation(libs.google.ai.generativeai)
    // HTTP client for streaming the Gemma model download (~1.5GB).
    implementation(libs.okhttp)

    debugImplementation(libs.bundles.compose.debug)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
