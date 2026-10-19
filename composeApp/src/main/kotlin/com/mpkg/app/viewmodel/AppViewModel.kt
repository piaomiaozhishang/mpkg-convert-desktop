package com.mpkg.app.viewmodel

import com.mpkg.app.AppInfo
import com.mpkg.app.errors.ErrorMessages
import com.mpkg.app.history.HistoryEntry
import com.mpkg.app.history.HistoryStore
import com.mpkg.app.platform.DesktopIntegration
import com.mpkg.app.settings.AppSettings
import com.mpkg.app.settings.SettingsStore
import com.mpkg.app.settings.WindowState
import com.mpkg.app.update.UpdateChecker
import com.mpkg.app.update.UpdateConfig
import com.mpkg.app.update.UpdateInfo
import com.mpkg.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 左侧导航的三个页面 + 关于页。 */
enum class Screen(val title: String, val subtitle: String) {
    CONVERT("转换", "拖入 .mpkg / .pkg，自动提取 4K 原图与 MP4"),
    HISTORY("历史记录", "最近的转换结果，点击可定位输出文件"),
    SETTINGS("设置", "输出目录、覆盖策略、并发与外观"),
    ABOUT("关于", "版本、许可与运行环境")
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
    private var updateCheckJob: Job? = null

    init {
        scope.launch { refreshThumbnails() }
        scheduleUpdateChecks()
    }

    /**
     * 启动即检查 + 运行期间每 12 小时复查（自动检查关闭时只保留手动检查）。
     *
     * 启动检查带一点延迟，先把窗口画出来再弹公告，避免「白窗等网络」。
     */
    private fun scheduleUpdateChecks() {
        val settings = _settings.value
        val due = System.currentTimeMillis() - settings.lastUpdateCheckAt >= UPDATE_CHECK_INTERVAL_MS
        if (settings.autoCheckUpdate && due) {
            scope.launch {
                delay(STARTUP_CHECK_DELAY_MS)
                checkForUpdate(manual = false)
            }
        }
        scope.launch {
            while (isActive) {
                delay(UPDATE_CHECK_INTERVAL_MS)
                if (_settings.value.autoCheckUpdate) checkForUpdate(manual = false)
            }
        }
    }

    // --- 检查更新 ---

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)

    /** 待展示的更新公告；null 表示没有。界面据此弹窗。 */
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    /**
     * 检查一次更新。
     *
     * @param manual 用户手动点的（设置页按钮）：无论结果如何都给 toast 反馈，
     *               且不受「忽略此版本」压制——手动检查是用户的明确意图；
     *               自动检查只在失败时静默记日志，不打扰用户。
     */
    fun checkForUpdate(manual: Boolean) {
        if (updateCheckJob?.isActive == true) return
        val settings = _settings.value
        if (!manual && !settings.autoCheckUpdate) return
        updateCheckJob = scope.launch {
            val result = UpdateChecker.check(
                currentVersion = AppInfo.VERSION
            )
            val now = System.currentTimeMillis()
            result.onSuccess { info ->
                // 成功与失败都记录时间：失败也节流，避免地址不通时每次启动都重试网络。
                updateSettings(quiet = true) { it.copy(lastUpdateCheckAt = now, lastUpdateCheckOk = true) }
                if (info == null) {
                    if (manual) showToast("当前已是最新版本 v${AppInfo.VERSION}")
                    return@onSuccess
                }
                // 「忽略此版本」只在 12 小时内压制自动提醒；过期自动恢复。
                val suppressed = !info.force &&
                    _settings.value.ignoreActiveFor(info.version, now, UPDATE_CHECK_INTERVAL_MS)
                if (suppressed && !manual) {
                    AppLog.i("版本 ${info.version} 在忽略期内，自动检查跳过提醒")
                    return@onSuccess
                }
                _updateAvailable.value = info
                AppLog.i("发现新版本 ${info.version}，弹出更新公告")
            }.onFailure {
                updateSettings(quiet = true) { it.copy(lastUpdateCheckAt = now, lastUpdateCheckOk = false) }
                if (manual) showToast("暂时无法检查更新，请检查网络后重试")
            }
        }
    }

    /** 关闭公告（不记住选择，下次检查还会提示）。 */
    fun dismissUpdate() {
        _updateAvailable.value = null
    }

    /** 「忽略此版本（12 小时内不再提醒）」：记录版本号与时间，过期后自动恢复提醒。 */
    fun ignoreUpdate(info: UpdateInfo) {
        updateSettings(quiet = true) {
            it.copy(ignoredUpdateVersion = info.version, ignoredUpdateAt = System.currentTimeMillis())
        }
        _updateAvailable.value = null
        showToast("已忽略版本 ${info.version}，12 小时内不再自动提醒")
    }

    /** 下载位置由发布者的版本清单和内置配置统一决定。 */
    fun downloadUpdate(info: UpdateInfo) {
        val url = UpdateConfig.downloadUrl(info)
        if (DesktopIntegration.openUrl(url)) {
            showToast("已在浏览器中打开下载页面")
        } else {
            showToast("无法打开下载页面，请检查默认浏览器后重试")
        }
        _updateAvailable.value = null
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

    /**
     * 后台读取历史缩略图到内存，界面只消费不可变 Map。
     *
     * 作用域默认跑在 [Dispatchers.Default]（CPU 密集），读文件要显式切到 IO，
     * 否则几百个小文件读盘会占用计算线程（审查报告 O5）。
     */
    private suspend fun refreshThumbnails() = withContext(Dispatchers.IO) {
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

    /** 缩略图缓存文件名 → 绝对路径；供任务卡片按需读取。 */
    fun thumbnailPath(name: String): String = File(historyStore.thumbnailDir(), name).absolutePath

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

        /** 自动检查更新间隔：12 小时。 */
        const val UPDATE_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

        /** 启动检查延迟：等窗口出来再查，避免开屏等待网络。 */
        const val STARTUP_CHECK_DELAY_MS = 1_500L
    }
}

/** 供 ViewModel 使用的默认作用域（结构化并发，禁止 GlobalScope）。 */
fun appScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
