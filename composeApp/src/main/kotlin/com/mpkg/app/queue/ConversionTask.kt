package com.mpkg.app.queue

import java.io.File
import java.util.UUID

/** 任务状态机（见报告 4.3 第 2 条）：等待(灰) → 解码中(主色+进度) → 已完成(成功色) / 失败(危险色)。 */
enum class TaskStatus {
    WAITING,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED
}

/** 一个产出的文件。 */
data class TaskOutput(
    val path: String,
    val fileName: String,
    val kind: String,
    val bytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val isPreview: Boolean = false
)

/** 列表里的一张任务卡片（不可变，由队列整体替换）。 */
data class ConversionTask(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val fileName: String = file.name,
    val fileSize: Long = 0L,
    val status: TaskStatus = TaskStatus.WAITING,
    /** 0f..1f */
    val progress: Float = 0f,
    val stage: String = "等待中",
    val packageMagic: String? = null,
    val entryCount: Int? = null,
    val isVideo: Boolean = false,
    val outputs: List<TaskOutput> = emptyList(),
    val errorTitle: String? = null,
    val errorDetail: String? = null,
    /**
     * 缩略图在缓存目录里的绝对路径；包内 preview 的字节直接落盘，不常驻任务对象。
     *
     * 背景：原先这里存的是 `ByteArray`，一张 preview 可达数百 KB，任务列表会随文件数
     * 线性吃掉内存（审查报告 O4）。改成路径后，界面按需读、读不到就画占位图。
     */
    val thumbnailPath: String? = null,
    /** 结果备注，例如「已跳过（目标已存在）」。正常完成时为空。 */
    val note: String? = null,
    val elapsedMs: Long = 0L
) {
    val removable: Boolean get() = status == TaskStatus.WAITING || status == TaskStatus.DONE ||
        status == TaskStatus.FAILED || status == TaskStatus.CANCELLED

    val cancellable: Boolean get() = status == TaskStatus.WAITING || status == TaskStatus.RUNNING

    /** 输出信息一行摘要，例如「3840×2160 PNG · 8.6 MB」。 */
    fun outputSummary(): String? {
        val primary = outputs.firstOrNull { !it.isPreview } ?: return null
        val size = when {
            primary.bytes >= 1024L * 1024 -> String.format("%.1f MB", primary.bytes / 1024.0 / 1024.0)
            else -> "${primary.bytes / 1024} KB"
        }
        val dims = if (primary.width > 0) "${primary.width}×${primary.height} " else ""
        val kind = primary.fileName.substringAfterLast('.', "").uppercase()
        return "$dims$kind · $size"
    }

    /** 包信息一行摘要，例如「PKGM0019 · 2.4 MB · 12 个条目」。 */
    fun packageSummary(): String {
        val parts = ArrayList<String>(3)
        packageMagic?.let { parts.add(it) }
        parts.add(formatSize(fileSize))
        entryCount?.let { parts.add("$it 个条目") }
        return parts.joinToString(" · ")
    }

    /**
     * 注意：**不要**重写 equals/hashCode 只比 id。
     * 任务列表通过 `MutableStateFlow<List<ConversionTask>>` 推送，StateFlow 用 equals
     * 判断是否发射；只比 id 会让「等待中 → 解码中 → 已完成」全部被判为没变化，界面永不刷新。
     * 列表项的稳定 key 由 LazyColumn 的 `key = { it.id }` 负责。
     */

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
