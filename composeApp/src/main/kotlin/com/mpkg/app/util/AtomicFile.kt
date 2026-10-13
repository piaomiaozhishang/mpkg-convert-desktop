package com.mpkg.app.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子写文件：先写同目录下的临时文件，再 rename 覆盖目标。
 *
 * 历史记录与设置都必须原子落盘（T3.3 验收项），避免断电/崩溃留下半个文件。
 */
object AtomicFile {

    fun writeText(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录: $parent")
        }
        val temp = File(parent, "${target.name}.tmp${System.nanoTime()}")
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                AppLog.d("文件系统不支持原子移动，退回普通替换: ${e.message}")
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                AppLog.w("临时文件未能删除: ${temp.absolutePath}")
            }
        }
    }

    fun readTextOrNull(target: File): String? {
        if (!target.isFile) return null
        return try {
            target.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            AppLog.w("读取失败 ${target.absolutePath}: ${e.message}")
            null
        }
    }
}
