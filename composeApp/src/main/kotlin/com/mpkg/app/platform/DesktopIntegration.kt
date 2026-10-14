package com.mpkg.app.platform

import com.mpkg.app.util.AppLog
import java.awt.Desktop
import java.awt.Image
import java.awt.Toolkit
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * 桌面系统集成：用系统默认程序打开图片/视频、定位到资源管理器、设置窗口图标。
 *
 * 「双击已完成卡片：图片直接预览，视频调用系统播放器」就落在这里（报告 4.3 第 4 条）。
 */
object DesktopIntegration {

    /** 用系统默认程序打开文件。 */
    fun openFile(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
                true
            } else {
                openWithSystemCommand(file)
            }
        } catch (e: Exception) {
            AppLog.w("打开文件失败 ${file.absolutePath}: ${e.message}")
            openWithSystemCommand(file)
        }
    }

    /**
     * 用系统默认浏览器打开网页（项目主页、新版本下载页）。
     *
     * 无头环境（测试、CLI）里 Desktop 不可用，直接返回 false，由调用方提示手动访问。
     */
    fun openUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI(trimmed)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                true
            } else {
                openUrlWithSystemCommand(trimmed)
            }
        } catch (e: Exception) {
            AppLog.w("打开链接失败 $trimmed: ${e.message}")
            openUrlWithSystemCommand(trimmed)
        }
    }

    private fun openUrlWithSystemCommand(url: String): Boolean = try {
        val command = when {
            PlatformPaths.isWindows -> listOf("cmd", "/c", "start", "", url)
            PlatformPaths.isMac -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        ProcessBuilder(command).start()
        true
    } catch (e: Exception) {
        AppLog.e("无法用浏览器打开链接: $url", e)
        false
    }

    /** 在文件管理器里定位到该文件（Windows 用 explorer /select）。 */
    fun revealInFileManager(file: File): Boolean {
        val target = if (file.exists()) file else file.parentFile ?: return false
        return try {
            when {
                PlatformPaths.isWindows && target.isFile ->
                    ProcessBuilder("explorer.exe", "/select,${target.absolutePath}").start().let { true }
                else -> openDirectory(if (target.isFile) target.parentFile else target)
            }
        } catch (e: Exception) {
            AppLog.w("定位文件失败: ${e.message}")
            false
        }
    }

    /** 打开目录。 */
    fun openDirectory(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir)
                true
            } else {
                openWithSystemCommand(dir)
            }
        } catch (e: Exception) {
            AppLog.w("打开目录失败 ${dir.absolutePath}: ${e.message}")
            openWithSystemCommand(dir)
        }
    }

    private fun openWithSystemCommand(file: File): Boolean = try {
        val command = when {
            PlatformPaths.isWindows -> listOf("cmd", "/c", "start", "", file.absolutePath)
            PlatformPaths.isMac -> listOf("open", file.absolutePath)
            else -> listOf("xdg-open", file.absolutePath)
        }
        ProcessBuilder(command).start()
        true
    } catch (e: Exception) {
        AppLog.e("无法用系统程序打开: ${file.absolutePath}", e)
        false
    }

    /** 读取应用图标（T4.1 产出物）；找不到时返回 null，窗口沿用系统默认图标。 */
    fun loadAppIcon(): Image? {
        val candidates = listOf(
            "/icons/app_256.png", "/icons/app_128.png", "/icons/app_64.png", "/icons/app_32.png"
        )
        for (path in candidates) {
            try {
                val stream = DesktopIntegration::class.java.getResourceAsStream(path) ?: continue
                stream.use { return ImageIO.read(it) }
            } catch (e: Exception) {
                AppLog.d("读取图标失败 $path: ${e.message}")
            }
        }
        return null
    }

    /** 多尺寸图标（Windows 任务栏/Alt-Tab 需要）。 */
    fun loadAppIconSet(): List<Image> {
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
        return sizes.mapNotNull { size ->
            try {
                val stream = DesktopIntegration::class.java.getResourceAsStream("/icons/app_$size.png")
                    ?: return@mapNotNull null
                stream.use { ImageIO.read(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toolkit(): Toolkit = Toolkit.getDefaultToolkit()
}
