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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Cloud Gemini (fallback when AICore unavailable)
    implementation(libs.google.ai.generativeai)

    debugImplementation(libs.bundles.compose.debug)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
