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

        // 仅构建 64 位 ABI
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 全局的 externalNativeBuild 配置放在 android 级别
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // 使用赋值而不是对不可变的 arguments 进行 += 操作
            arguments = listOf("-DCMAKE_C_FLAGS=-O3 -std=c11")
        }
    }
}
