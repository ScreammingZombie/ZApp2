plugins {
    id("com.android.application")
}

android {
    namespace = "com.pvm.zombiemusiclibrary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pvm.zombiemusiclibrary"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    // One fixed key for every build, so a new version installs over the old one (same signature).
    signingConfigs {
        create("fixed") {
            storeFile = file("zombie.keystore")
            storePassword = "zombie2026"
            keyAlias = "zombie"
            keyPassword = "zombie2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// No external libraries: the app only uses what Android already includes.
