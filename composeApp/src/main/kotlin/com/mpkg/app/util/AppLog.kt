package com.mpkg.app.util

/**
 * 统一日志出口（见《03 · 开发规范》第 2 节第 6 条）。
 *
 * 全部日志写 stderr，避免污染 CLI 的 stdout 结果；禁止在业务代码里散落 println。
 */
object AppLog {

    /** 调试日志开关；发布版默认关闭 debug 级别。 */
    var debugEnabled: Boolean = false

    fun d(message: String) {
        if (debugEnabled) write("D", message)
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, error: Throwable? = null) {
        write("E", message)
        // 堆栈只在开启调试时打印：正常的格式错误已有可读文案，不该刷屏。
        if (debugEnabled) error?.printStackTrace(System.err)
    }

    private fun write(level: String, message: String) {
        System.err.println("[mpkg][$level] $message")
    }
}
