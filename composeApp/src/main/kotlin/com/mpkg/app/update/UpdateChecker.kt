package com.mpkg.app.update

import com.mpkg.app.util.AppLog
import com.mpkg.app.util.JsonParser
import com.mpkg.app.util.asBoolean
import com.mpkg.app.util.asStringOrNull
import com.mpkg.app.util.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端发布的版本信息（对应 Gitee 上的 update.json）。
 *
 * @param version 远端版本号，与本地 [com.mpkg.app.AppInfo.VERSION] 做数值比较
 * @param downloadUrl 「下载新版本」要打开的地址；为空时回退到设置里的下载地址
 * @param notes 更新说明，展示在公告窗里
 * @param force true 时公告窗不可忽略、不可关闭（强制更新，在 update.json 里发 `"force": true`）
 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String? = null,
    val notes: String? = null,
    val force: Boolean = false
)

/**
 * 检查更新（走 Gitee，国内可直接访问；GitHub raw 对部分网络环境不通）。
 *
 * 版本文件由用户自行发布，默认地址见 [com.mpkg.app.settings.AppSettings.DEFAULT_UPDATE_CHECK_URL]，
 * 支持两种格式：
 * 1. JSON：`{"version":"1.0.1","downloadUrl":"https://...","notes":"...","force":false}`
 * 2. 纯文本：第一行写版本号（下载地址回到设置里配）
 *
 * 任何网络/解析失败都作为异常返回，由调用方决定提示文案；绝不影响主流程。
 */
object UpdateChecker {

    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY_CHARS = 64 * 1024

    /**
     * 拉取远端版本并判断是否需要更新。
     *
     * @return success + null = 已是最新；success + info = 有新版本；failure = 检查失败
     */
    suspend fun check(url: String, currentVersion: String): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val info = fetch(url)
                Result.success(if (isNewer(info.version, currentVersion)) info else null)
            } catch (e: Exception) {
                AppLog.w("检查更新失败（$url）: ${e.message}")
                Result.failure(e)
            }
        }

    /** 远端版本是否比本地新（逐段数值比较，1.0.10 > 1.0.9）。 */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    fun compare(a: String, b: String): Int {
        val sa = a.split('.', '-', '+', ' ', '_')
        val sb = b.split('.', '-', '+', ' ', '_')
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val va = sa.getOrNull(i)?.toIntOrNull()
            val vb = sb.getOrNull(i)?.toIntOrNull()
            if (va != null && vb != null) {
                if (va != vb) return va.compareTo(vb)
            } else {
                // 非数字段（如 beta）：按字符串比，空段视为更旧
                val c = sa.getOrNull(i).orEmpty().compareTo(sb.getOrNull(i).orEmpty())
                if (c != 0) return c
            }
        }
        return 0
    }

    private fun fetch(url: String): UpdateInfo {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "更新地址必须以 http:// 或 https:// 开头"
        }
        val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "mpkg-tool update-checker")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > MAX_BODY_CHARS) throw IOException("版本文件异常：内容过大")
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): UpdateInfo {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val root = JsonParser.parseOrNull(trimmed) ?: throw IOException("版本信息不是合法 JSON")
            val version = root["version"].asStringOrNull()?.trim()
            require(!version.isNullOrEmpty()) { "版本信息缺少 version 字段" }
            return UpdateInfo(
                version = version,
                downloadUrl = root["downloadUrl"].asStringOrNull()?.trim()?.ifEmpty { null },
                notes = root["notes"].asStringOrNull()?.trim()?.ifEmpty { null },
                force = root["force"].asBoolean(false)
            )
        }
        // 纯文本：第一行非空内容即版本号
        val version = trimmed.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        require(!version.isNullOrEmpty()) { "版本信息为空" }
        return UpdateInfo(version = version)
    }
}
