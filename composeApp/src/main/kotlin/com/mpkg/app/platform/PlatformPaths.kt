package com.mpkg.app.platform

import java.io.File

/**
 * 平台相关路径约定（见《02 · 架构与目录结构》第 7 节）。
 *
 * - Windows：`%APPDATA%\mpkg-tool`
 * - macOS：`~/Library/Application Support/mpkg-tool`
 * - Linux：`~/.config/mpkg-tool`
 */
object PlatformPaths {

    const val APP_DIR_NAME = "mpkg-tool"

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean get() = osName.contains("win")
    val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")
    val isLinux: Boolean get() = osName.contains("nux") || osName.contains("nix")

    /** 应用配置目录（不存在时由调用方按需创建）。 */
    fun configDir(): File {
        val home = File(System.getProperty("user.home") ?: ".")
        return when {
            isWindows -> {
                val appData = System.getenv("APPDATA")
                if (appData.isNullOrBlank()) File(home, "AppData/Roaming/$APP_DIR_NAME")
                else File(appData, APP_DIR_NAME)
            }
            isMac -> File(home, "Library/Application Support/$APP_DIR_NAME")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (xdg.isNullOrBlank()) File(home, ".config/$APP_DIR_NAME") else File(xdg, APP_DIR_NAME)
            }
        }
    }

    fun settingsFile(): File = File(configDir(), "settings.json")

    fun historyFile(): File = File(configDir(), "history.json")

    fun thumbnailCacheDir(): File = File(configDir(), "thumbs")

    fun logFile(): File = File(configDir(), "mpkg-tool.log")

    /** 默认输出目录：`~/Pictures/Wallpapers`（用户可在设置里改）。 */
    fun defaultOutputDir(): File {
        val home = File(System.getProperty("user.home") ?: ".")
        val userProfile = System.getenv("USERPROFILE")
        val base = if (isWindows && !userProfile.isNullOrBlank()) File(userProfile) else home
        return File(base, "Pictures/Wallpapers")
    }

    /** 平台描述，用于「关于」页与日志。 */
    fun describe(): String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} / JDK ${System.getProperty("java.version")}"
}
