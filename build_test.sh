#!/bin/bash
# PedigreeApp2 构建测试脚本 (Termux 环境)
# 注意：完整 APK 构建需要 Android SDK + NDK
set -e

PROJECT_DIR="$HOME/PedigreeApp2"
BUILD_DIR="$PROJECT_DIR/build_output"

echo "========================================="
echo "  PedigreeApp2 构建验证"
echo "========================================="
echo ""

# 1. 检查必要工具
echo "1. 检查构建工具..."
if command -v gradle &> /dev/null; then
    echo "   ✓ Gradle: $(gradle --version | head -1)"
else
    echo "   ⚠ Gradle 未安装 (可使用 gradlew wrapper)"
fi

if command -v cmake &> /dev/null; then
    echo "   ✓ CMake: $(cmake --version | head -1)"
else
    echo "   ✗ CMake 未安装"
fi

if [ -n "$ANDROID_NDK_HOME" ] || [ -n "$ANDROID_HOME" ]; then
    echo "   ✓ Android SDK/NDK 环境变量已设置"
else
    echo "   ⚠ Android SDK/NDK 环境变量未设置"
    echo "      需要设置 ANDROID_HOME 和 ANDROID_NDK_HOME"
fi

echo ""

# 2. 验证项目结构
echo "2. 验证项目完整性..."
MISSING=0
for f in settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml; do
    if [ -f "$PROJECT_DIR/$f" ]; then
        echo "   ✓ $f"
    else
        echo "   ✗ $f 缺失"
        MISSING=$((MISSING + 1))
    fi
done

if [ $MISSING -gt 0 ]; then
    echo ""
    echo "❌ 项目不完整，缺少 $MISSING 个关键文件"
    exit 1
fi

echo ""
echo "✅ 项目结构完整"
echo ""

# 3. 检查 native-lib.c 语法
echo "3. 验证 native-lib.c 语法..."
cd "$PROJECT_DIR/app/src/main/cpp"
if gcc -fsyntax-only -I/usr/include native-lib.c 2>&1; then
    echo "   ✓ C 语法检查通过"
else
    echo "   ✗ C 语法检查失败"
    exit 1
fi

echo ""

# 4. 输出构建指南
echo "========================================="
echo "  构建指南"
echo "========================================="
echo ""
echo "在 Android Studio 中:"
echo "  1. File → Open → 选择 $PROJECT_DIR"
echo "  2. 等待 Gradle Sync"
echo "  3. Build → Make Project (Ctrl+F9)"
echo "  4. Run → Run 'app' (Shift+F10)"
echo ""
echo "命令行构建 (需要 Android SDK + NDK):"
echo "  export ANDROID_HOME=/path/to/android-sdk"
echo "  export ANDROID_NDK_HOME=/path/to/android-ndk"
echo "  cd $PROJECT_DIR"
echo "  ./gradlew assembleDebug"
echo ""
echo "输出 APK 位置:"
echo "  \$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo ""

# 5. 可选：尝试本地编译 native 库 (仅测试)
echo "========================================="
echo "  测试编译 native 库 (无 NDK)..."
echo "========================================="

# 尝试用系统编译器编译 .c 文件为 .o
cd "$PROJECT_DIR/app/src/main/cpp"
if gcc -c -fPIC -O2 native-lib.c -o /tmp/pedigree_test.o 2>&1; then
    echo "✓ native-lib.c 可编译为对象文件"
    ls -la /tmp/pedigree_test.o
    rm -f /tmp/pedigree_test.o
else
    echo "✗ native-lib.c 编译失败"
fi

echo ""
echo "========================================="
echo "  验证完成"
echo "========================================="