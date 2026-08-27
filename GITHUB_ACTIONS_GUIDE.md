# 🤖 GitHub Actions 自动编译 Android APK 指南

本仓库已配置 GitHub Actions 工作流 **"Build Android APK"**（`.github/workflows/build-apk.yml`），在云端自动编译并签名 APK。

## 📌 触发条件（与工作流配置一致）

| 触发方式 | 行为 |
|---|---|
| 推送 `v*` 格式的标签 | 构建 + 自动上传 APK 到 GitHub Release |
| 在 GitHub 页面创建 Release | 构建 + 上传（标记为预发布） |
| 手动触发 (workflow_dispatch) | 仅构建，产物保留在 Artifacts，不上传 Release |

> 注意：普通 push / PR **不会**触发构建；如需修改触发方式请编辑 `build-apk.yml` 的 `on:` 段。

### 构建产物
- **Debug APK**：Artifacts 中的 `pedigree-app-debug`，保留 30 天
- **Release APK**：打标签时永久附到 Releases 页面（通配符匹配 `*.apk`）

## 🔐 所需 Secrets 配置

首次使用前，在仓库 **Settings → Secrets and variables → Actions** 中配置：

| Secret 名称 | 内容 |
|---|---|
| `SIGNING_KEY` | keystore.jks 文件的 Base64 编码（`base64 -w0 keystore.jks`） |
| `KEY_STORE_PASSWORD` | keystore 密码 |
| `ALIAS` | 签名密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

未配置签名 Secrets 时 Debug 包仍可正常构建；Release 包签名会失败或产生未签名产物。

生成测试用 keystore：

```bash
keytool -genkey -v -keystore pedigree.keystore -alias pedigree \
    -keyalg RSA -keysize 2048 -validity 10000
```

## 🚀 使用步骤

### 1. 推送代码到 GitHub

```bash
git remote add origin https://github.com/你的用户名/dragon.git
git branch -M main
git push -u origin main
```

### 2. 手动测试构建

1. 进入仓库 **Actions** 标签页
2. 左侧选择 **"Build Android APK"**
3. 点击 **Run workflow** → 选择分支 → 确认运行
4. 约 3-5 分钟后在运行记录底部下载 `pedigree-app-debug`

### 3. 发布正式版

```bash
git tag v1.1.0
git push origin v1.1.0
```

推送标签后工作流自动构建签名 APK 并附带在 Release 中，同时自动生成发布说明。

## 💡 注意事项

- 免费账户每月 2000 分钟额度，单次构建约 3-5 分钟（含 NDK/CMake 缓存后更快）
- 工作流使用 `gradle/actions/setup-gradle@v4` 缓存 Gradle 与依赖
- Release 上传步骤仅在标签/Release 事件时执行（`if` 条件保护），手动触发不会报 422 错误