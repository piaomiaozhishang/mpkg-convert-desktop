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
 * - 选文件用 `java.awt.FileDialog`：Windows/macOS 上是原生对话框，观感与系统一致；
 * - 选目录用 `JFileChooser(DIRECTORIES_ONLY)`：FileDialog 在 Windows 上无法选目录。
 */
object FileDialogs {

    /** 支持的文件类型过滤（报告第 1 节：.mpkg / .pkg / scene.pkg）。 */
    val SUPPORTED_EXTENSIONS = setOf("mpkg", "pkg")

    fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

    /** 多选包文件；取消返回空列表。 */
    fun choosePackageFiles(parent: java.awt.Window?): List<File> {
        val frame = parent as? Frame
        val dialog = FileDialog(frame, "选择壁纸包（可多选）", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS }
        dialog.isVisible = true
        val files = dialog.files?.toList().orEmpty()
        val dir = dialog.directory
        dialog.dispose()
        if (files.isEmpty()) return emptyList()
        return files.map { if (it.isAbsolute) it else File(dir ?: ".", it.name) }
    }

    /** 选择输出目录；取消返回 null。 */
    fun chooseOutputDir(parent: java.awt.Window?, current: File): File? {
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
