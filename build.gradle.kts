// 根构建脚本：只声明插件，不写模块配置（见《02 · 架构与目录结构》第 6 节）。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}
