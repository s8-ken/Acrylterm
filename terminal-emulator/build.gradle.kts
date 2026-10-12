plugins {
    alias(libs.plugins.android.library)
}

// Vendored from termux/termux-app (terminal-emulator + TerminalRenderer from terminal-view).
// These two modules are Apache License 2.0 (see NOTICE). The JNI/subprocess parts are unused:
// this app feeds the emulator from an SSH channel instead of a local PTY.
android {
    namespace = "com.termux.emulator"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
