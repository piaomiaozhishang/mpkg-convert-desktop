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
    /** 自动检查更新：开启后每 12 小时查一次，启动时距上次检查超期也会查。 */
    val autoCheckUpdate: Boolean = true,
    /** 版本信息地址（Gitee raw，JSON 或纯文本版本号）。 */
    val updateCheckUrl: String = DEFAULT_UPDATE_CHECK_URL,
    /** 新版本下载地址；留空则用 update.json 里的 downloadUrl。 */
    val updateDownloadUrl: String = "",
    /** 上次检查时间（epoch 毫秒），用于 12 小时节流；失败也记录，避免地址不通时每次启动都重试。 */
    val lastUpdateCheckAt: Long = 0L,
    /** 上次检查结果：null=从未检查；true=成功；false=失败（地址不通或网络异常）。 */
    val lastUpdateCheckOk: Boolean? = null,
    /** 用户点过「忽略此版本」的版本号。 */
    val ignoredUpdateVersion: String? = null,
    /** 忽略发生的时间（epoch 毫秒）；距离现在超过 12 小时自动恢复提醒。 */
    val ignoredUpdateAt: Long = 0L,
    val window: WindowState = WindowState()
) {
    /** 并发度收敛到 1..8，避免配置被写坏时开出一堆线程。 */
    fun normalized(): AppSettings = copy(
        concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        updateCheckUrl = updateCheckUrl.trim(),
        updateDownloadUrl = updateDownloadUrl.trim()
    )

    /** 实际使用的版本检查地址（设置为空时回退默认值）。 */
    fun effectiveUpdateCheckUrl(): String = updateCheckUrl.ifBlank { DEFAULT_UPDATE_CHECK_URL }

    /**
     * 「忽略此版本」是否仍在有效期内：同版本、且忽略时间距 [now] 不超过 [intervalMs]。
     *
     * 过期后自动恢复提醒（用户反馈：忽略应当是 12 小时内不再提示，不是永久拉黑）。
     * 强制更新（force）由调用方另行判断，不走这里。
     */
    fun ignoreActiveFor(remoteVersion: String, now: Long, intervalMs: Long): Boolean {
        if (ignoredUpdateVersion.isNullOrEmpty()) return false
        if (ignoredUpdateVersion != remoteVersion) return false
        val age = now - ignoredUpdateAt
        return age in 0 until intervalMs
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 2
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 8

        /**
         * 默认版本检查地址：Gitee raw（GitHub raw 在国内部分网络下打不开）。
         * 仓库里 `update.json` 由用户自行发布，格式见 UpdateChecker 文档。
         */
        const val DEFAULT_UPDATE_CHECK_URL =
            "https://gitee.com/piaomiaozhishang/mpkg-convert-desktop/raw/master/update.json"
    }
}
