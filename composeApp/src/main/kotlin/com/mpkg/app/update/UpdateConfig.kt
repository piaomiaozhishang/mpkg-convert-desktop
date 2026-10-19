package com.mpkg.app.update

import com.mpkg.app.AppInfo

/** 发布者维护的更新配置，不从用户设置、环境变量或界面读取覆盖值。 */
internal object UpdateConfig {
    const val CHECK_URL =
        "https://gitee.com/piaomiaozhishang/mpkg-convert-desktop/raw/master/update.json"

    // 新版本下载地址优先由发布者的版本清单提供。
    const val FALLBACK_DOWNLOAD_URL = AppInfo.PROJECT_URL

    fun downloadUrl(info: UpdateInfo): String = info.downloadUrl?.takeIf { it.isNotBlank() }
        ?: FALLBACK_DOWNLOAD_URL
}
