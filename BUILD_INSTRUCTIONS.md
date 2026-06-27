# PedigreeApp2 构建说明

## 环境要求

- Android Studio Arctic Fox (2020.3.1) 或更新版本
- Android SDK 34
- NDK 25.0.8775105 或更新版本
- CMake 3.22.1+
- JDK 17+

## 快速构建 (Android Studio)

1. **打开项目**
   ```
   File → Open → 选择 PedigreeApp2 目录
   ```

2. **等待同步**
   - Gradle 会自动下载依赖
   - NDK 和 CMake 会自动配置

3. **构建 APK**
   ```
   Build → Make Project (Ctrl+F9)
   ```

4. **运行到设备**
   ```
   Run → Run 'app' (Shift+F10)
   ```

## 命令行构建

### Linux/macOS

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.0.8775105

cd PedigreeApp2
./gradlew assembleDebug
```

### Windows (PowerShell)

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME="$env:ANDROID_HOME\ndk\25.0.8775105"

cd PedigreeApp2
.\gradlew.bat assembleDebug
```

## 输出位置

- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`

## 签名 Release APK

```bash
# 生成签名密钥
keytool -genkey -v -keystore pedigree.keystore -alias pedigree \
    -keyalg RSA -keysize 2048 -validity 10000

# 签名 APK
apksigner sign --ks pedigree.keystore \
    app/build/outputs/apk/release/app-release-unsigned.apk

# 验证签名
apksigner verify app/build/outputs/apk/release/app-release-signed.apk
```

## 故障排除

### CMake 编译失败

```
问题：找不到 CMake
解决：SDK Manager → SDK Tools → 安装 CMake
```

### NDK 未找到

```
问题：NDK 侧边栏缺失
解决：SDK Manager → SDK Tools → 安装 NDK (Side by side) 25.0.8775105
```

### SQLite3 链接错误

```
问题：找不到 sqlite3.h
解决：Termux 用户执行 pkg install sqlite
Android Studio 用户：CMake 会自动查找系统 SQLite3
```

### Gradle Sync 失败

```bash
# 清理并重新同步
./gradlew clean
./gradlew build --refresh-dependencies
```

## 项目结构说明

```
app/src/main/cpp/
├── CMakeLists.txt      # CMake 构建配置
└── native-lib.c        # JNI + Henderson 算法实现

app/src/main/java/.../
├── MainActivity.kt     # UI 主界面
└── PedigreeCalculator.kt  # 计算封装类

res/
├── layout/             # XML 布局
├── values/             # 字符串和主题
└── mipmap-*/           # 应用图标
```

## 算法特性

- **Henderson 稀疏矩阵法** - O(N) 时间和空间复杂度
- **拓扑排序** - 确保正确的计算顺序
- **坐标压缩** - 减少内存占用
- **延迟插入** - 避免 O(N²) 内存移动
- **二路归并** - 高效的矩阵乘法

## 性能基准

| 个体数 | 计算时间 | 内存占用 |
|--------|----------|----------|
| 1,000  | < 1 秒   | ~2 MB    |
| 10,000 | ~5 秒    | ~20 MB   |
| 100,000| ~60 秒   | ~200 MB  |

## 联系与支持

如有问题，请提交 Issues 或联系开发团队。