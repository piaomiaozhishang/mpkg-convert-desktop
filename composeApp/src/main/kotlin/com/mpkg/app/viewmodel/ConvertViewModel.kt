package com.mpkg.app.viewmodel

import com.mpkg.app.history.HistoryEntry
import com.mpkg.app.platform.FileDialogs
import com.mpkg.app.queue.ConversionQueue
import com.mpkg.app.queue.ConversionTask
import com.mpkg.app.queue.TaskStatus
import com.mpkg.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * 转换页状态（任务卡 T2.2 / T2.3 / T2.4）。
 *
 * 入队与开始分离：拖入/选择只进列表（等待态），点「开始转换」才真正调度，
 * 便于用户在开跑前调整输出目录与覆盖策略。
 */
class ConvertViewModel(
    private val scope: CoroutineScope,
    private val app: AppViewModel,
    private val onOpenFile: (File) -> Unit
) {

    private val _tasks = MutableStateFlow<List<ConversionTask>>(emptyList())
    val tasks: StateFlow<List<ConversionTask>> = _tasks.asStateFlow()

    /** 拖拽悬停高亮（Compose 层负责视觉反馈）。 */
    private val _dragActive = MutableStateFlow(false)
    val dragActive: StateFlow<Boolean> = _dragActive.asStateFlow()

    /** 当前选中的卡片，供 Delete 快捷键使用。 */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun select(id: String) {
        _selectedId.value = if (_selectedId.value == id) null else id
    }

    private val queue = ConversionQueue(
        scope = scope,
        settingsProvider = { app.settings.value },
        onUpdate = ::upsert,
        onCompleted = ::afterTaskFinished
    )

    /** 最近一次拖拽处理的时间戳，用于 Compose 与 AWT 双通道去重。 */
    private var lastDropSignature: String? = null
    private var lastDropAt: Long = 0L

    // --- 入队 ---

    /**
     * 加入待转换文件。
     *
     * @return 实际入队的数量；不支持的文件会被忽略并提示
     */
    fun addFiles(files: List<File>): Int {
        if (files.isEmpty()) return 0
        val supported = files.filter { it.isFile && FileDialogs.isSupported(it) }
        val rejected = files.size - supported.size
        if (supported.isEmpty()) {
            app.showToast("只支持 .mpkg / .pkg 文件，已忽略 $rejected 个不支持的项")
            return 0
        }
        val existing = _tasks.value.map { it.file.absolutePath }.toSet()
        val fresh = supported.filter { it.absolutePath !in existing }
        val duplicates = supported.size - fresh.size
        if (fresh.isEmpty()) {
            app.showToast("这些文件已经在列表里了")
            return 0
        }
        _tasks.update { current ->
            current + fresh.map { file ->
                ConversionTask(file = file, fileName = file.name, fileSize = file.length())
            }
        }
        val notes = ArrayList<String>(2)
        if (rejected > 0) notes.add("忽略 $rejected 个不支持的项")
        if (duplicates > 0) notes.add("跳过 $duplicates 个重复项")
        app.showToast(
            if (notes.isEmpty()) "已加入 ${fresh.size} 个文件" else "已加入 ${fresh.size} 个文件（${notes.joinToString("，")}）"
        )
        return fresh.size
    }

    /** 供 Compose 拖拽与 AWT 兜底共用的入口，做去重避免同一个 drop 被处理两次。 */
    fun handleDrop(files: List<File>) {
        val signature = files.map { it.absolutePath }.sorted().joinToString("|")
        val now = System.currentTimeMillis()
        if (signature == lastDropSignature && now - lastDropAt < DROP_DEDUPE_WINDOW_MS) {
            AppLog.d("忽略重复的拖拽事件")
            return
        }
        lastDropSignature = signature
        lastDropAt = now
        addFiles(files)
    }

    fun setDragActive(active: Boolean) {
        _dragActive.value = active
    }

    fun remove(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        if (_selectedId.value == id) _selectedId.value = null
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.status == TaskStatus.WAITING || it.status == TaskStatus.RUNNING } }
        _selectedId.value = null
    }

    fun removeAll() {
        queue.cancelAll()
        _tasks.value = emptyList()
        _selectedId.value = null
    }

    // --- 调度 ---

    fun start() {
        val waiting = _tasks.value.filter { it.status == TaskStatus.WAITING }
        if (waiting.isEmpty()) return
        waiting.forEach(queue::submit)
        app.showToast("开始转换 ${waiting.size} 个文件")
    }

    fun cancel(id: String) {
        queue.cancel(id)
        // 立刻反映为「已取消」，不等底层线程真正退出（core 解码不可中断）。
        _tasks.update { list ->
            list.map { if (it.id == id && it.status != TaskStatus.DONE) it.copy(status = TaskStatus.CANCELLED, stage = "已取消") else it }
        }
    }

    fun cancelAll() {
        queue.cancelAll()
        _tasks.update { list ->
            list.map {
                if (it.status == TaskStatus.WAITING || it.status == TaskStatus.RUNNING) {
                    it.copy(status = TaskStatus.CANCELLED, stage = "已取消")
                } else {
                    it
                }
            }
        }
    }

    fun retry(id: String) {
        _tasks.update { list ->
            list.map {
                if (it.id == id) it.copy(status = TaskStatus.WAITING, progress = 0f, stage = "等待中", errorTitle = null, errorDetail = null)
                else it
            }
        }
        _tasks.value.firstOrNull { it.id == id }?.let(queue::submit)
    }

    fun openOutput(task: ConversionTask) {
        val target = task.outputs.firstOrNull { !it.isPreview } ?: task.outputs.firstOrNull()
        if (target == null) {
            app.showToast("这个任务还没有输出文件")
            return
        }
        onOpenFile(File(target.path))
    }

    // --- 汇总信息（底部操作栏用） ---

    fun waitingCount(): Int = _tasks.value.count { it.status == TaskStatus.WAITING }

    fun runningCount(): Int = _tasks.value.count { it.status == TaskStatus.RUNNING }

    fun doneCount(): Int = _tasks.value.count { it.status == TaskStatus.DONE }

    fun failedCount(): Int = _tasks.value.count { it.status == TaskStatus.FAILED }

    /** 粗略耗时预估：静态壁纸按 6 秒，视频按 1.5 秒，再按并发摊平。 */
    fun estimateSeconds(): Int {
        val waiting = _tasks.value.filter { it.status == TaskStatus.WAITING }
        if (waiting.isEmpty()) return 0
        val perFile = waiting.sumOf { if (it.isVideo) 1.5 else 6.0 }
        val concurrency = app.settings.value.concurrency.coerceAtLeast(1)
        return Math.ceil(perFile / concurrency).toInt().coerceAtLeast(1)
    }

    private fun upsert(task: ConversionTask) {
        _tasks.update { list ->
            val index = list.indexOfFirst { it.id == task.id }
            if (index < 0) list else list.toMutableList().also { it[index] = task }
        }
    }

    private fun afterTaskFinished(task: ConversionTask) {
        if (task.status != TaskStatus.DONE) return
        val first = task.outputs.firstOrNull { !it.isPreview } ?: return
        val kind = if (first.fileName.substringAfterLast('.', "").lowercase() == "mp4") "video" else "image"
        var thumbName: String? = null
        task.thumbnailBytes?.let { bytes ->
            thumbName = app.saveThumbnail(task.id, bytes)
        }
        app.addHistory(
            HistoryEntry(
                id = task.id,
                fileName = task.fileName,
                sourcePath = task.file.absolutePath,
                kind = kind,
                timestamp = System.currentTimeMillis(),
                outputs = task.outputs.map { it.path },
                thumbFileName = thumbName,
                thumbLastUsed = System.currentTimeMillis(),
                note = task.outputSummary()
            )
        )
    }

    companion object {
        private const val DROP_DEDUPE_WINDOW_MS = 900L
    }
}
