plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.shibaken.androidterminal"
    testOptions { unitTests.isReturnDefaultValues = true }
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shibaken.androidterminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.shibaken.androidterminal.CyanVeilSmokeInstrumentation"
        // tsbridge.aar (gomobile) is built for arm64 only.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Lets the app know at compile time whether the optional Tailscale AAR is bundled,
        // so it never has to load the (large) Go library just to find out.
        buildConfigField("boolean", "HAS_TSBRIDGE", file("libs/tsbridge.aar").exists().toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal use: sign release builds with the debug key so they install directly.
            signingConfig = signingConfigs.getByName("debug")
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
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":terminal-emulator"))

    // Optional embedded Tailscale (built by tsbridge/build.sh). The app compiles and runs
    // without it; TailscaleManager detects it at runtime via reflection.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.luaj.jse)
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
