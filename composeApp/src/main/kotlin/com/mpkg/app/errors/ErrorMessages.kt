package com.mpkg.app.errors

import com.doubao.repkg.core.FormatException

/**
 * 错误文案映射表（任务卡 T3.5）。
 *
 * 文案集中管理，便于后续多语言；UI 与 CLI 都从这里取用户可见文案，
 * core 抛出的技术性英文异常不直接展示给用户。
 */
object ErrorMessages {

    const val NOT_A_PACKAGE = "不是有效的 .mpkg / .pkg 文件"
    const val NOT_A_TEXTURE = "不是有效的 .tex 纹理文件"
    const val TRUNCATED = "文件不完整，可能下载或导出中断"
    const val ANIMATED_TEXF = "动态序列帧壁纸暂不支持，已跳过"
    const val UNSUPPORTED_TEXTURE = "该纹理格式暂不支持解码"
    const val NO_CONTENT = "该壁纸包内没有可提取的图片或视频"
    const val WRITE_FAILED = "无法写入输出目录，请检查权限或更换目录"
    const val READ_FAILED = "无法读取该文件，请检查文件是否存在或被占用"
    const val DISK_FULL = "磁盘空间不足，请清理后重试"
    const val LZ4_UNAVAILABLE = "压缩数据无法解压，文件可能损坏"
    const val UNKNOWN = "处理失败"

    /** 把异常映射成一句用户能看懂的话。 */
    fun describe(error: Throwable): String = when (error) {
        is java.io.EOFException -> TRUNCATED
        is java.io.FileNotFoundException -> READ_FAILED
        is java.nio.file.AccessDeniedException -> WRITE_FAILED
        is java.nio.file.NoSuchFileException -> READ_FAILED
        is OutOfMemoryError -> "内存不足，请关闭其它程序或减小并发数"
        is FormatException -> describeFormat(error.message.orEmpty())
        // IOException 一律说成「无法写入」会误导：磁盘满、源文件被占用都是读侧问题。
        is java.io.IOException -> describeIo(error)
        else -> UNKNOWN
    }

    /**
     * IOException 的细分。
     *
     * 兜底仍是 WRITE_FAILED（历史上绝大多数 IOException 出现在写盘阶段），
     * 但磁盘满与源文件读取失败要先被挑出来，否则用户会去查一个根本没问题的输出目录。
     */
    private fun describeIo(error: java.io.IOException): String {
        val m = error.message.orEmpty().lowercase()
        return when {
            "no space" in m || "not enough space" in m || "没有足够的空间" in m -> DISK_FULL
            m.startsWith("读取") -> READ_FAILED
            else -> WRITE_FAILED
        }
    }

    /** core 的 FormatException message → 中文文案。消息匹配不区分大小写。 */
    private fun describeFormat(raw: String): String {
        val m = raw.lowercase()
        return when {
            "not a wallpaper engine package" in m -> NOT_A_PACKAGE
            "not a tex file" in m -> NOT_A_TEXTURE
            "unsupported tex image magic" in m -> NOT_A_TEXTURE
            "texf" in m -> ANIMATED_TEXF
            "entry count" in m || "entry too large" in m -> TRUNCATED
            "string length cannot be negative" in m -> TRUNCATED
            "size mismatch" in m || "lz4" in m -> LZ4_UNAVAILABLE
            "unsupported tex container" in m -> UNSUPPORTED_TEXTURE
            "format" in m -> UNSUPPORTED_TEXTURE
            else -> UNKNOWN
        }
    }
}
