package com.mpkg.app.viewmodel

import com.mpkg.app.errors.ErrorMessages
import com.mpkg.app.history.HistoryEntry
import com.mpkg.app.history.HistoryStore
import com.mpkg.app.settings.AppSettings
import com.mpkg.app.settings.SettingsStore
import com.mpkg.app.settings.WindowState
import com.mpkg.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 左侧导航的三个页面 + 关于页。 */
enum class Screen(val title: String, val subtitle: String) {
    CONVERT("转换", "拖入 .mpkg / .pkg，自动提取 4K 原图与 MP4"),
    HISTORY("历史记录", "最近的转换结果，点击可定位输出文件"),
    SETTINGS("设置", "输出目录、覆盖策略、并发与外观"),
    ABOUT("关于", "出处、许可与版本信息")
}

/**
 * 应用级状态（任务卡 T2.1 / T3.3 / T3.4）。
 *
 * 单一数据源：导航、设置、历史都从这里取；设置改动做去抖后原子落盘。
 */
class AppViewModel(private val scope: CoroutineScope) {

    private val settingsStore = SettingsStore()
    private val historyStore = HistoryStore()

    private val _settings = MutableStateFlow(settingsStore.load().normalized())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow(Screen.CONVERT)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /** 历史缩略图字节（后台预读，避免在 Composable 里做 IO）。 */
    private val _thumbnails = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ByteArray>> = _thumbnails.asStateFlow()

    /** 一次性提示（复制到剪贴板、写入失败等），界面消费后置空。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var saveJob: Job? = null
    private var windowSaveJob: Job? = null
    private var thumbnailJob: Job? = null

    init {
        scope.launch { refreshThumbnails() }
    }

    fun navigate(screen: Screen) {
        _screen.value = screen
    }

    /** 修改设置：立即生效 + 去抖持久化。 */
    fun updateSettings(quiet: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).normalized()
        if (next == _settings.value) return
        _settings.value = next
        scheduleSave(quiet)
    }

    fun updateWindowState(transform: (WindowState) -> WindowState) {
        val next = transform(_settings.value.window)
        if (next == _settings.value.window) return
        _settings.value = _settings.value.copy(window = next)
        // 拖动窗口会高频触发，单独用更长的去抖，避免写盘抖动。
        windowSaveJob?.cancel()
        windowSaveJob = scope.launch {
            delay(WINDOW_SAVE_DEBOUNCE_MS)
            settingsStore.save(_settings.value)
        }
    }

    private fun scheduleSave(quiet: Boolean) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(if (quiet) SETTINGS_SAVE_DEBOUNCE_MS else 0)
            settingsStore.save(_settings.value)
        }
    }

    // --- 历史记录 ---

    fun addHistory(entry: HistoryEntry) {
        historyStore.add(entry)
        _history.value = historyStore.all()
        reloadThumbnails()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = historyStore.all()
        _thumbnails.value = emptyMap()
        showToast("历史记录已清空")
    }

    /** 后台读取历史缩略图到内存，界面只消费不可变 Map。 */
    private suspend fun refreshThumbnails() {
        val entries = _history.value
        val loaded = HashMap<String, ByteArray>(entries.size)
        entries.forEach { entry ->
            val file = historyStore.thumbnailFile(entry) ?: return@forEach
            runCatching { loaded[entry.id] = file.readBytes() }
        }
        _thumbnails.value = loaded
    }

    fun reloadThumbnails() {
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch { refreshThumbnails() }
    }

    fun touchThumbnail(id: String) {
        historyStore.touchThumbnail(id)
        _history.value = historyStore.all()
    }

    fun thumbnailFile(entry: HistoryEntry): File? = historyStore.thumbnailFile(entry)

    fun saveThumbnail(id: String, bytes: ByteArray): String? = historyStore.saveThumbnail(id, bytes)

    /** 打开历史条目的输出文件；文件不存在时提示而不是静默失败。 */
    fun openHistoryEntry(entry: HistoryEntry, onOpen: (File) -> Unit) {
        val target = entry.outputs.map(::File).firstOrNull { it.isFile }
        if (target == null) {
            showToast("输出文件已不存在，可能已被移动或删除")
            return
        }
        onOpen(target)
    }

    // --- 提示 ---

    fun showToast(message: String) {
        _toast.value = message
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun reportError(where: String, error: Throwable) {
        AppLog.e("$where 失败: ${error.message}", error)
        showToast(ErrorMessages.describe(error))
    }

    companion object {
        const val SETTINGS_SAVE_DEBOUNCE_MS = 300L
        const val WINDOW_SAVE_DEBOUNCE_MS = 700L
    }
}

/** 供 ViewModel 使用的默认作用域（结构化并发，禁止 GlobalScope）。 */
fun appScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
