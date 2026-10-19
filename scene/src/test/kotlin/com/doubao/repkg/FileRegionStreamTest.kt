package com.doubao.repkg

import com.mpkg.scene.core.SeekableData
import com.mpkg.scene.core.SliceInputStream
import com.mpkg.scene.core.copyStream
import com.mpkg.scene.core.openFileRegion
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FileRegionStreamTest {
    @Test
    fun manyChunksReuseOneBufferIncludingTheShortFinalChunk() {
        var first: ByteArray? = null
        var calls = 0
        val source = object : SeekableData {
            override val size = 10003L
            override fun readAt(offset: Long, length: Int): ByteArray =
                error("Streaming must use the caller-owned buffer")

            override fun readInto(offset: Long, buffer: ByteArray, length: Int) {
                if (first == null) first = buffer else assertSame(first, buffer)
                calls++
                repeat(length) { buffer[it] = ((offset + it) % 251).toByte() }
            }
        }
        val output = ByteArrayOutputStream()
        SliceInputStream(source, 7, 9993, chunkSize = 1024).use {
            assertEquals(9993L, copyStream(it, output, bufferSize = 137))
            assertEquals(-1, it.read())
        }
        assertEquals(10, calls)
        assertArrayEquals(ByteArray(9993) { ((it + 7) % 251).toByte() }, output.toByteArray())
    }

    @Test
    fun realFileRegionIsByteExactAcrossMultipleBuffers() {
        val file = File.createTempFile("mpkg-region", ".pkg")
        try {
            val bytes = ByteArray(2 * 1024 * 1024 + 513) { (it % 239).toByte() }
            file.writeBytes(bytes)
            val output = ByteArrayOutputStream()
            openFileRegion(file, 123, bytes.size.toLong() - 321).use {
                copyStream(it, output)
            }
            assertArrayEquals(bytes.copyOfRange(123, bytes.size - 198), output.toByteArray())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsOutOfBoundsRangesAndClosesOnlyOnce() {
        val source = object : SeekableData {
            override val size = 10L
            override fun readAt(offset: Long, length: Int) = ByteArray(length)
        }
        for ((start, length) in listOf(-1L to 1L, 9L to 2L, 0L to -1L, Long.MAX_VALUE to 1L)) {
            try {
                SliceInputStream(source, start, length)
                fail("Invalid range accepted: $start/$length")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        var closes = 0
        val input = SliceInputStream(source, 10, 0, onClose = { closes++ })
        assertEquals(-1, input.read())
        input.close()
        input.close()
        assertEquals(1, closes)
    }
}
