# PedigreeApp2 - 动物系谱分析系统 Android 版

基于 Henderson 混合模型方程组的动物亲缘关系矩阵计算器，使用纯 C 实现核心算法，通过 JNI 在 Android 上运行。

## 🚀 一键获取 APK

### 方法 1: 从 GitHub Actions 下载 (推荐)

1. 点击 **Actions** 标签页
2. 选择最新的 **"Build Android APK"** 工作流
3. 点击对应的运行记录
4. 在底部 **Artifacts** 区域下载 `pedigree-app-debug`
5. 解压后得到 `app-debug.apk`

### 方法 2: 从 Releases 下载

如果已打标签 (tag)，APK 会自动上传到 Releases 页面。

---

## 📱 安装与使用

1. 将 APK 传输到 Android 设备
2. 允许安装未知来源应用
3. 安装并运行

**注意**: 首次运行会自动创建测试数据库并计算示例系谱数据。

---

## 🛠️ 本地构建指南

```bash
# 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- NDK 26

# 命令行构建
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
./gradlew assembleDebug

# 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📊 算法特性

- ✅ Henderson 混合模型方程组
- ✅ 拓扑排序 (Kahn 算法)
- ✅ 稀疏矩阵存储 (内存优化)
- ✅ 三路归并 (O(N) 复杂度)
- ✅ SQLite 输入/输出
- ✅ JNI 原生加速

---

## 📝 许可证

MIT License