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

        // 修复: 使用 += 追加元素到现有的 MutableSet 中，而不是使用 = 重新赋值
        ndk {
            abiFilters += "arm64-v8a"
        }

        // CMake 编译参数配置
        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_C_FLAGS=-O3 -std=c11")
            }
        }
    }

    // 全局的 externalNativeBuild 仅保留 path 和 version
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    // 添加 Material Design 3 (Compose) 依赖
    implementation("androidx.compose.material3:material3:1.2.1")
    
    // 如果你正在使用 Jetpack Compose，通常还需要以下基础依赖：
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.activity:activity-compose:1.9.0")
}
