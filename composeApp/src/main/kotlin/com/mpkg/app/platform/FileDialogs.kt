package com.mpkg.app.platform

import com.mpkg.app.util.AppLog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * 文件与目录选择（任务卡 T2.2 / T2.5）。
 *
 * - Windows 文件和目录均使用现代 IFileOpenDialog，带系统导航和固定位置；
 * - 其他平台沿用 AWT 文件对话框和 Swing 目录选择器。
 */
object FileDialogs {

    /** 支持的文件类型过滤（报告第 1 节：.mpkg / .pkg / scene.pkg）。 */
    val SUPPORTED_EXTENSIONS = setOf("mpkg", "pkg")

    fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

    /** 多选包文件；取消返回空列表。 */
    fun choosePackageFiles(parent: java.awt.Window?): List<File> {
        if (PlatformPaths.isWindows) return WindowsShell.choose(parent, folders = false)
        // Compose 的 window（ComposeWindow）是 java.awt.Window 而不是 Frame：
        // 直接 `parent as? Frame` 会得到 null，建出无主对话框——不随应用窗口居中、
        // 可能被应用窗口盖住、任务栏里多出一个条目，观感就像一个脱节的小窗口。
        // 正确做法是给对话框一个挂在应用窗口上的宿主：用一个位于应用窗口中心、
        // 不可见的 1x1 Dialog 作锚点（Dialog 的构造器接受 Window 宿主，Frame 不接受），
        // 原生对话框因此获得完整归属链：居中、模态、永远浮在应用窗口之上。
        val anchor = createAnchor(parent)
        return try {
            val dialog = FileDialog(anchor, "选择壁纸包（可多选）", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS }
            dialog.isVisible = true
            val files = dialog.files?.toList().orEmpty()
            val dir = dialog.directory
            dialog.dispose()
            if (files.isEmpty()) return emptyList()
            files.map { if (it.isAbsolute) it else File(dir ?: ".", it.name) }
        } finally {
            anchor.dispose()
            // 兜底路径造的不可见 Frame 也要释放；应用窗口本身不能动。
            val anchorOwner = anchor.owner
            if (anchorOwner != null && anchorOwner != parent) anchorOwner.dispose()
        }
    }

    /**
     * 在应用窗口中心放一个不可见的 1x1 Dialog，作为原生文件对话框的宿主。
     *
     * 宿主挂在应用窗口（ComposeWindow）上：原生对话框以宿主中心居中，模态关系
     * 沿归属链传递，视觉上就是贴在应用窗口上、且不会跑到应用背后。
     * 宿主位置取不到时（异常/无父窗口）退化为屏幕中心。
     */
    private fun createAnchor(parent: java.awt.Window?): java.awt.Dialog {
        val center = parent?.let { win ->
            try {
                val b = win.bounds
                java.awt.Point(b.x + b.width / 2, b.y + b.height / 2)
            } catch (e: Exception) {
                AppLog.d("读取宿主窗口位置失败，改用屏幕中心: ${e.message}")
                null
            }
        } ?: run {
            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
            java.awt.Point(screen.width / 2, screen.height / 2)
        }
        val owner = parent ?: run {
            // 没有父窗口时造一个不可见的兜底宿主，保证对话框始终有主。
            java.awt.Frame().apply {
                isUndecorated = true
                size = java.awt.Dimension(1, 1)
                location = center
                isVisible = true
            }
        }
        return java.awt.Dialog(owner).apply {
            isUndecorated = true
            size = java.awt.Dimension(1, 1)
            location = center
            isVisible = true
        }
    }

    /** 选择输出目录；取消返回 null。 */
    fun chooseOutputDir(parent: java.awt.Window?, current: File): File? {
        if (PlatformPaths.isWindows) return WindowsShell.choose(parent, folders = true, current).firstOrNull()
        return try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            AppLog.d("设置系统外观失败: ${e.message}")
        }.let {
            val chooser = JFileChooser(current.takeIf { it.isDirectory } ?: current.parentFile)
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = "选择输出目录"
            chooser.isMultiSelectionEnabled = false
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }
}
