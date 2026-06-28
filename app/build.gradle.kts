plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.pedigreeapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pedigreeapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "6.0"

        ndk {
            // Only build 64-bit ABI
            abiFilters += setOf("arm64-v8a")
        }
    }

    // Global externalNativeBuild configuration at android level
    externalNativeBuild {
        cmake {
            // path expects a File in the Kotlin DSL
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"

            // Pass C flags to CMake via arguments rather than mutating cFlags
            // This sets the CMake variable CMAKE_C_FLAGS to include -O3 and -std=c11
            arguments += listOf("-DCMAKE_C_FLAGS=-O3 -std=c11")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
