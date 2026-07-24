import com.google.firebase.appdistribution.gradle.firebaseAppDistribution

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

android {
    namespace = "com.berry.netchaos"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.berry.netchaos"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            firebaseAppDistribution {
                artifactType = "APK"
                releaseNotes = "Test build"
                // Comma-separated tester emails, e.g. "a@example.com,b@example.com",
                // or testersFile = "path/to/testers.txt". Alternatively use a
                // Firebase Console tester group: groups = "qa-team"
                testers = ""
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}