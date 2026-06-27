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
                // 开启 C11 标准和最高优化级别
                cFlags "-O3 -std=c11" 
            }
        }
        
        // 只编译 64 位架构
        ndk {
            abiFilters += listOf("arm64-v8a") 
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1" 
        }
    }
}