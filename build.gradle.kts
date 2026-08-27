// 顶层构建文件，声明项目中所有模块共用的插件版本
plugins {
    // 升级 AGP (Android Gradle Plugin) 到 8.5.2，修复布尔属性弃用警告
    id("com.android.application") version "8.5.2" apply false
    // 升级 Kotlin 插件到 1.9.23，移除对 Convention 类型的使用
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}
