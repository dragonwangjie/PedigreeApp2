# 🤖 GitHub Actions 自动编译 Android APK 指南

本仓库已配置 GitHub Actions 自动在云端编译 Android APK。

## 📌 配置说明

### 触发条件
- ✅ Push 到 `main` 或 `master` 分支
- ✅ Pull Request 到 `main` 或 `master` 分支
- ✅ 手动触发 (Actions → "Run workflow")
- ✅ 创建 Tag 时自动上传到 Releases

### 构建产物
- **Debug APK**: 保留 30 天
- **Release APK**: 永久保存在 Releases 页面

## 🚀 使用步骤

### 1. 推送代码到 GitHub
创建 GitHub 仓库并推送代码：

```bash
# 初始化 Git
cd ~/PedigreeApp2
git init
git add .
git commit -m "Initial commit"

# 关联 GitHub (替换为你的仓库地址)
git remote add origin https://github.com/你的用户名/PedigreeApp2.git

# 推送到 main 分支
git branch -M main
git push -u origin main
```

### 2. 等待自动构建
推送后等待约 **3-5 分钟**，GitHub Actions 会自动开始构建。

### 3. 下载 APK
- 进入 **Actions** 标签页
- 选择 "Build Android APK" 工作流
- 点击最新运行记录
- 下载底部 **Artifacts** 中的 `pedigree-app-debug.zip`
- 解压得到 `app-debug.apk`

## 🏷️ 发布正式版 (可选)

打标签会自动上传到 Releases：

```bash
git tag v1.0.0
git push origin v1.0.0
```

APK 会自动命名为 `pedigree-app-v1.0.0.apk`并附带在 Release 中。

## ⚡ 手动测试构建

1. 进入 Actions 标签页
2. 左侧选择 "Build Android APK"
3. 点击 "Run workflow"
4. 选择分支后点击 "Run workflow"

## 💡 注意事项
- 免费账户每月 2000 分钟额度，单次构建约 3-5 分钟
- 默认生成 Debug 版本 (含调试符号)
- 正式发布建议添加签名配置生成 Signed APK