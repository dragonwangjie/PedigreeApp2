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

    // 1. 新增：签名配置（从环境变量读取，适配 GitHub Actions）
    signingConfigs {
        create("release") {
            // 路径与 GitHub Actions 中 base64 解码生成的路径保持一致
            storeFile = file("keystore.jks") 
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 2. 新增：将签名配置应用到 release 构建类型
            signingConfig = signingConfigs.getByName("release")
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
    
    // Jetpack Compose 基础依赖
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.activity:activity-compose:1.9.0")
}
