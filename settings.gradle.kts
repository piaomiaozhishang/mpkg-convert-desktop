// 仓库顺序：国内镜像优先（本项目在中文网络环境下构建，直连上游经常握手失败），
// 上游仓库作为兜底。依赖与插件版本仍由 gradle/libs.versions.toml 统一锁定。
// 注意：pluginManagement 必须是本文件中的第一个语句块。
pluginManagement {
    repositories {
        listOf(
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/gradle-plugin",
            "https://maven.aliyun.com/repository/google",
        ).forEach { maven(it) }
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        listOf(
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/google",
        ).forEach { maven(it) }
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "mpkg-tool"

include(":core")
include(":composeApp")
