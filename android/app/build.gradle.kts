plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.shipmonster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.shipmonster.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.2.$runNumber"
    }

    signingConfigs {
        // Shared debug key, committed on purpose so every CI build can install over the last one.
        // It is public. Never use it for a release build.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FileProvider, for handing the share image to other apps
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // Android only ships stubs of org.json to unit tests; this is the real thing
    testImplementation("org.json:json:20240303")
}
