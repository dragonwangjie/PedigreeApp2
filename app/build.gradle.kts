android {
    namespace = "com.example.pedigreeapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pedigreeapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "6.0"

        externalNativeBuild { 
            cmake { 
                // 启用 C11 标准和最高优化级别
                // 使用列表字符串正确追加标志
                cFlags += listOf("-O3", "-std=c11") 
            } 
        }
    }
    
    // 仅构建 64 位 ABI 
    ndk { 
        abiFilters += listOf("arm64-v8a") 
    }

    // 配置 CMake 编译原生代码
    externalNativeBuild { 
        cmake { 
            // 在 Kotlin DSL 中，path 期望接收 String 类型
            path = "src/main/cpp/CMakeLists.txt" 
            version = "3.22.1" 
        } 
    }
}
