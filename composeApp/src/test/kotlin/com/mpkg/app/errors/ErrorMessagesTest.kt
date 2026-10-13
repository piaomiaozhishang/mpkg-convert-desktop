package com.mpkg.app.errors

import com.doubao.repkg.core.FormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException

/** 错误文案映射（任务卡 T3.5）：交接文档「已知边界」四类 + 写入失败都要有对应提示。 */
class ErrorMessagesTest {

    @Test
    fun `四类已知边界都有专门文案`() {
        assertEquals(
            ErrorMessages.NOT_A_PACKAGE,
            ErrorMessages.describe(FormatException("Not a Wallpaper Engine package (magic='XXXX0000')"))
        )
        assertEquals(
            ErrorMessages.ANIMATED_TEXF,
            ErrorMessages.describe(FormatException("Unsupported TEX container 'TEXF0001'"))
        )
        assertEquals(
            ErrorMessages.UNSUPPORTED_TEXTURE,
            ErrorMessages.describe(FormatException("unsupported tex format: 12"))
        )
        assertEquals(
            ErrorMessages.TRUNCATED,
            ErrorMessages.describe(FormatException("Invalid entry count: 200000000"))
        )
    }

    @Test
    fun `截断与写入失败分别映射到文件不完整与无法写入`() {
        assertEquals(ErrorMessages.TRUNCATED, ErrorMessages.describe(EOFException("read past end of data")))
        assertEquals(ErrorMessages.WRITE_FAILED, ErrorMessages.describe(java.io.IOException("disk full")))
        assertEquals(ErrorMessages.READ_FAILED, ErrorMessages.describe(FileNotFoundException("nope")))
    }

    @Test
    fun `LZ4 与内存不足有专门文案`() {
        assertEquals(ErrorMessages.LZ4_UNAVAILABLE, ErrorMessages.describe(FormatException("LZ4 size mismatch: read 3 of 9")))
        assertNotEquals(ErrorMessages.UNKNOWN, ErrorMessages.describe(OutOfMemoryError("java heap space")))
    }

    @Test
    fun `文案里不出现技术术语与英文异常名`() {
        val samples = listOf(
            FormatException("Not a TEX file (magic1='TEXV9999')"),
            FormatException("String length cannot be negative"),
            EOFException("Unexpected end of file at offset 16")
        )
        samples.forEach { error ->
            val text = ErrorMessages.describe(error)
            listOf("Exception", "null", "offset", "magic").forEach { term ->
                assertNotEquals("文案不应包含技术术语 $term: $text", true, text.contains(term, ignoreCase = true))
            }
        }
    }

    @Test
    fun `未知异常回退到通用文案而不是泄露堆栈`() {
        assertEquals(ErrorMessages.UNKNOWN, ErrorMessages.describe(IllegalStateException("boom")))
    }
}
