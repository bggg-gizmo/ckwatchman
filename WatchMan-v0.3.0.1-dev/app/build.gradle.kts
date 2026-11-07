plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bggg.watchman"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.bggg.watchman"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.3.0"
        testInstrumentationRunner = "com.bggg.watchman.CorrectiveInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
}
