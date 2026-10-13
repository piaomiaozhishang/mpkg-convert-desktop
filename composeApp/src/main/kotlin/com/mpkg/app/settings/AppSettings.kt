package com.mpkg.app.settings

import com.mpkg.app.export.OverwritePolicy
import com.mpkg.app.platform.PlatformPaths
import com.mpkg.app.ui.theme.AccentPreset
import com.mpkg.app.ui.theme.ThemeMode

/** 解码方式。GPU 第一版不实现，仅占位（见任务卡 T3.4 注意事项）。 */
enum class DecodeMode(val displayName: String, val available: Boolean) {
    CPU("CPU 解码（推荐）", true),
    GPU("GPU 解码（尚未实现）", false);

    companion object {
        fun fromName(name: String?): DecodeMode = entries.firstOrNull { it.name == name } ?: CPU
    }
}

/** 窗口位置与尺寸记忆（任务卡 T2.1）。 */
data class WindowState(
    val x: Int = UNSET,
    val y: Int = UNSET,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val maximized: Boolean = false
) {
    companion object {
        const val UNSET = Int.MIN_VALUE
        const val DEFAULT_WIDTH = 1100
        const val DEFAULT_HEIGHT = 720
        const val MIN_WIDTH = 960
        const val MIN_HEIGHT = 600
    }
}

/**
 * 应用设置（任务卡 T3.4）。
 *
 * 默认值面向「新用户零配置即可用」：输出到 `~/Pictures/Wallpapers`，
 * 覆盖策略取最安全的「重命名」，并发 2，深色主题。
 */
data class AppSettings(
    val outputDir: String = PlatformPaths.defaultOutputDir().absolutePath,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.RENAME,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val exportPreview: Boolean = false,
    val decodeMode: DecodeMode = DecodeMode.CPU,
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val accent: AccentPreset = AccentPreset.DEFAULT,
    val reduceMotion: Boolean = false,
    val openOutputWhenDone: Boolean = false,
    val window: WindowState = WindowState()
) {
    /** 并发度收敛到 1..8，避免配置被写坏时开出一堆线程。 */
    fun normalized(): AppSettings = copy(concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY))

    companion object {
        const val DEFAULT_CONCURRENCY = 2
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 8
    }
}
