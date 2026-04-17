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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey}\"")
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
