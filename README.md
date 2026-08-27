# PedigreeApp2 - 动物系谱分析系统 Android 版

基于 Henderson 混合模型方程组的动物亲缘关系矩阵计算器。核心算法以纯 C 实现并经 JNI 加速，UI 采用 Kotlin 构建，数据通过 SQLite 输入输出。

## ✨ 功能总览

| 功能 | 说明 |
|---|---|
| 🧮 **近交系数计算** | Henderson 稀疏表格法计算全群近交系数 F，结果写入 `Inbreeding(ID, F)` 表 |
| 🔗 **亲缘关系矩阵** | 同步得到加性亲缘矩阵 A；种群 ≤ 300 时自动导出 `Relationship(ID1, ID2, A)` 成对关系表，支持分页浏览 |
| 🔍 **配对亲缘速查** | 无需全量计算，直接查询任意两只动物间的亲缘系数 A(id1, id2) |
| 📋 **数据管理** | 手动录入、CSV 批量导入（幂等覆盖）、外部数据库切换与校验、输入/结果库导出 |
| 🛡️ **健壮性** | 循环亲子系谱检测、无效数据库校验、内存熔断保护、线程安全的计算入口 |

## 🚀 一键获取 APK

### 方法 1: 从 GitHub Actions 下载 (推荐)

1. 点击 **Actions** 标签页
2. 选择最新的 **"Build Android APK"** 工作流
3. 点击对应的运行记录
4. 在底部 **Artifacts** 区域下载 `pedigree-app-debug`
5. 解压后得到 `app-debug.apk`

### 方法 2: 从 Releases 下载

如果已打标签 (tag)，APK 会自动上传到 Releases 页面。

## 📱 安装与使用

1. 将 APK 传输到 Android 设备并安装（需允许未知来源）
2. 首次运行自动创建测试系谱：`1、2 为奠基者；3 = 1×2；4 = 3×2`
3. 点击 **开始计算**，完成后自动跳转结果页查看各个体近交系数
4. 通过底部导航 **计算** 菜单可：
   - 浏览亲缘关系 (A 矩阵) —— 分页加载成对关系数据
   - 查询配对亲缘 —— 输入两个 ID 即得 A 值（如 A(4,3) = 0.75）
5. 「使用外部数据库」支持载入含 `Pedigree(ID, SireID, DamID)` 表的 SQLite 文件

**示例数据理论值**：F₄ = 0.25（亲子交配）；A(4,3) = 0.75、A(3,2) = 0.5、A(1,2) = 0（无关奠基者）。

---

## 🛠️ 本地构建指南

```bash
# 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- NDK 26
- CMake 3.22.1+

# 命令行构建
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
./gradlew assembleDebug

# 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📊 算法特性

- ✅ Henderson 混合模型方程组 / 稀疏表格法
- ✅ Kahn 拓扑排序（父母先于后代，循环系谱自动报错）
- ✅ 坐标压缩 + 二分查找定位个体
- ✅ 下三角稀疏存储，零值剪枝 + 内存池统一分配
- ✅ 内存熔断上限（约 1600 万稀疏元素）防止极端数据拖垮设备
- ✅ SQLite 全流程输入/输出，JNI 原生加速
- ✅ 计算入口互斥锁保护，可安全并发调用

## ⚡ 性能参考

| 种群规模 | 实测耗时* |
|---|---|
| 4（内置示例） | < 0.01 秒 |
| 2,000（随机系谱） | ~0.31 秒 |

\* 中端 arm64 设备/桌面级模拟环境实测；耗时随稀疏度变化，深血缘结构会略慢。

> 注：种群 > 300 时为避免 O(N²) 存储，仅输出近交系数表，不导出成对关系表。

---

## 📁 项目结构

```
app/src/main/cpp/
├── CMakeLists.txt      # CMake 构建配置
├── native-lib.c        # Henderson 引擎 + JNI 入口（读库→拓扑排序→递推→写库）
├── sqlite3.c/h         # SQLite amalgamation 源码
└── ...
app/src/main/java/com/example/pedigreeapp/
├── MainActivity.kt         # 主界面：数据管理、计算调度、结果浏览、配对查询
├── PedigreeDbHelper.kt     # 内部输入库管理
├── PedigreeRecord.kt       # 数据模型
├── PedigreeAdapter.kt      # 系谱列表适配器
├── InbreedingAdapter.kt    # 近交系数列表适配器
└── RelationshipAdapter.kt  # 关系对分页列表适配器（含加载更多）
res/
├── layout/             # XML 布局
├── menu/               # 底部导航菜单
├── values/             # 字符串、颜色、主题
└── mipmap-*/           # 应用图标
```

### 结果数据库格式

`pedigree_results.db` 包含两张表：

```sql
-- 近交系数（总是写出）
CREATE TABLE Inbreeding(ID INTEGER PRIMARY KEY, F REAL NOT NULL);
-- 成对亲缘关系（种群 ≤ 300 时填充）
CREATE TABLE Relationship(ID1 INTEGER, ID2 INTEGER, A REAL NOT NULL);
```

---

## 🧪 桌面端验证方法（可选）

核心引擎可在桌面 Linux 上独立验证（无需 Android SDK），便于 CI 或本地快速回归：

```bash
cd app/src/main/cpp
gcc -fsyntax-only -std=c99 -I<android_headers_stub> native-lib.c   # 语法检查
# 或参照仓库测试思路：用桩替换 jni.h/android/log.h，
# 将 native-lib.c 与 sqlite3.c 编译为宿主可执行文件，核对示例系谱的理论值
```

已验证的正确性基线：F₄=0.25；A(4,3)=0.75；A(3,1)=A(3,2)=0.5；A(1,2)=0。

---

## 📝 许可证

MIT License