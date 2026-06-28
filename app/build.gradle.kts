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
            // 使用 += 单个字符串追加，避免对不可变集合重新赋值
            abiFilters += "arm64-v8a"
        }

        // 若需为特定构建变体传入 CMake 参数，使用 arguments 而不是直接修改 cFlags/cppFlags
        externalNativeBuild {
            cmake {
                // 将 C 编译器标志通过 CMake 参数传入，避免 Kotlin DSL 的可变性问题
                arguments += listOf("-DCMAKE_C_FLAGS=-O3 -std=c11")
            }
        }
    }

    // 全局的 externalNativeBuild 配置放在 android 级别
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
