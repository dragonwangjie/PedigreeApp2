# PedigreeApp2 构建说明

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- Android SDK 34
- NDK 26（r25 及以上版本亦可）
- CMake 3.22.1+
- JDK 17+

## 快速构建

### 方式一：命令行 (Linux/macOS)

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.*

cd dragon
./gradlew assembleDebug
```

### 方式二：Windows (PowerShell)

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME="$env:ANDROID_HOME\ndk\26.x.x"

cd dragon
.\gradlew.bat assembleDebug
```

### 方式三：Android Studio

1. **打开项目**：`File → Open → 选择 dragon 目录`
2. **等待同步**：Gradle 会自动下载依赖，NDK 和 CMake 按提示安装
3. **构建 APK**：`Build → Make Project (Ctrl+F9)`
4. **运行到设备**：`Run → Run 'app' (Shift+F10)`

## 输出位置

- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`（CI 环境下自动签名）

## CI/CD 签名说明

Release 构建的签名信息从环境变量读取（由 GitHub Actions 注入）：

| 环境变量 | 对应 GitHub Secret |
|---|---|
| `SIGNING_STORE_PASSWORD` | `KEY_STORE_PASSWORD` |
| `SIGNING_KEY_ALIAS` | `ALIAS` |
| `SIGNING_KEY_PASSWORD` | `KEY_PASSWORD` |

Keystore 文件由工作流从 Secret `SIGNING_KEY`（Base64）解码生成到 `app/keystore.jks`。本地构建 Debug 包无需这些配置。

---

## 项目结构说明

```
app/src/main/cpp/
├── CMakeLists.txt      # CMake 构建配置（C99）
├── native-lib.c        # Henderson 引擎 + JNI 入口
└── sqlite3.c/h         # SQLite amalgamation 源码

app/src/main/java/com/example/pedigreeapp/
├── MainActivity.kt         # 主界面与业务调度
├── PedigreeDbHelper.kt     # 内部输入库 (pedigree_input.db)
├── PedigreeRecord.kt       # 数据模型
├── PedigreeAdapter.kt      # 系谱数据列表适配器
├── InbreedingAdapter.kt    # 近交系数列表适配器
└── RelationshipAdapter.kt  # 关系对分页列表适配器

res/
├── layout/             # XML 布局
├── menu/               # 底部导航菜单
├── values/             # 字符串和主题
└── mipmap-*/           # 应用图标
```

### JNI 接口清单

| Kotlin 声明 | 功能 | 返回值 |
|---|---|---|
| `runCalculationNative(inputPath, outputPath)` | 全量计算：读库 → 坐标压缩 → 拓扑排序 → 稀疏递推 → 写结果库 | 结果描述字符串 |
| `queryRelationshipNative(inputPath, id1, id2)` | 配对亲缘查询 A(id1,id2)，只计算到所需行 | Double；个体不存在返回 NaN |

> 注意：JNI 符号名与包名 `com.example.pedigreeapp`、类名 `MainActivity` 强绑定，重命名包或类时必须同步修改 C 函数名。

---

## 核心算法说明

计算流程（`native-lib.c`）：

1. **读取**：从输入库 `Pedigree(ID, SireID, DamID)` 加载记录
2. **压缩**：所有出现过的 ID 排序去重映射为连续下标；缺失双亲补为奠基者
3. **排序**：Kahn 拓扑排序保证父母先于后代；存在循环亲子关系时报错终止
4. **递推**：按拓扑序逐行构造稀疏下三角矩阵
   - 非对角元：`a_kj = 0.5 × (a_sire,j + a_dam,j)`
   - 对角元：`a_kk = 1 + F_k`，其中 `F_k = 0.5 × a_sire,dam`
   - 取值统一走 `getA(x,j)`（定位到行 max、列 min），零值剪枝，内存池分配
5. **输出**：写 `Inbreeding(ID, F)`；种群 ≤ 300 时额外填充 `Relationship(ID1, ID2, A)`

关键阈值常量（见文件头宏定义）：零值剪枝 `EPS_ZERO=1e-14`、关系导出阈值 `MAX_REL_EXPORT_N=300`、内存熔断约 1600 万稀疏元素。

## 正确性验证基线

内置示例系谱（首启自动创建）：`1、2 为奠基者；3 = 1×2；4 = 3×2`。

| 量 | 期望值 |
|---|---|
| F₁ = F₂ = F₃ | 0 |
| F₄ | 0.25 |
| A(1,2) | 0（无关奠基者） |
| A(3,1) = A(3,2) | 0.5 |
| A(4,1) | 0.25 |
| A(4,2) = A(4,3) | 0.75 |

任何算法改动后应核对上表数值不发生变化。

### 桌面端快速验证（无需 Android SDK）

用最小桩头文件替换 `jni.h` / `android/log.h` 后，可将引擎编译为宿主可执行程序做回归测试：

```bash
gcc -O2 -std=gnu99 \
    -I<stub_dir> -I app/src/main/cpp \
    test_driver.c app/src/main/cpp/native-lib.c app/src/main/cpp/sqlite3.c \
    -lm -lpthread -o pedigree_test && ./pedigree_test
```

参考性能基线（桌面 arm64）：2000 个随机系谱个体全量计算约 0.31 秒。

---

## 故障排除

### CMake 编译失败

```
问题：找不到 CMake
解决：SDK Manager → SDK Tools → 安装 CMake 3.22.1+
```

### NDK 未找到

```
问题：配置阶段报 NDK 版本缺失
解决：SDK Manager → SDK Tools → 安装 NDK (Side by side)，并确认 ANDROID_NDK_HOME 指向正确目录
```

### SQLite 编译警告刷屏

```
现象：sqlite3.c 产生大量告警
说明：CMakeLists.txt 已对该文件单独设置 -w 屏蔽，属预期行为，不影响构建
```

### JNI UnsatisfiedLinkError

```
问题：运行时崩溃 java.lang.UnsatisfiedLinkError
解决：
1. 确认未修改包名 com.example.pedigreeapp 与类名 MainActivity
   （JNI 函数名 Java_com_example_pedigreeapp_MainActivity_* 与其强绑定）
2. 确认 abiFilters 覆盖了目标设备架构（当前：arm64-v8a / armeabi-v7a / x86_64）
```

### 计算报"系谱存在循环亲子关系"

```
现象：状态栏提示 xx/xx 个个体无法排序
原因：数据中存在 A 的父亲是 B、B 的父亲又是 A 这类环
解决：检查并修正输入数据后重新计算
```

### Gradle Sync 失败

```bash
# 清理并重新同步
./gradlew clean
./gradlew build --refresh-dependencies
```

## 联系与支持

如有问题，请提交 Issues 或联系开发团队。