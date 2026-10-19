package com.mpkg.scene.core

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * Random-access binary data source used by the parsers.
 * Two implementations are provided so the exact same parsing code runs on
 * Android (file channel) and in plain JVM unit tests (byte array).
 */
interface SeekableData {
    val size: Long

    /** Read exactly [length] bytes starting at absolute [offset]. */
    fun readAt(offset: Long, length: Int): ByteArray

    /** Fill a caller-owned buffer; file-backed sources avoid allocating per chunk. */
    fun readInto(offset: Long, buffer: ByteArray, length: Int) {
        readAt(offset, length).copyInto(buffer)
    }
}

/** Backed by a seekable [FileChannel]. */
class ChannelSeekableData(private val channel: FileChannel) : SeekableData {
    override val size: Long get() = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length == 0) return EMPTY
        return ByteArray(length).also { readInto(offset, it, length) }
    }

    override fun readInto(offset: Long, buffer: ByteArray, length: Int) {
        require(length in 0..buffer.size)
        val out = ByteBuffer.wrap(buffer, 0, length)
        var pos = offset
        while (out.hasRemaining()) {
            val read = channel.read(out, pos)
            if (read < 0) throw EOFException("Unexpected end of file at offset $pos")
            pos += read
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/** Backed by an in-memory byte array (used in unit tests). */
class ByteArraySeekableData(private val data: ByteArray) : SeekableData {
    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length == 0) return EMPTY
        if (offset < 0 || offset > data.size) throw EOFException("offset out of range: $offset")
        if (offset + length > data.size) throw EOFException("read past end of data")
        return data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/**
 * A windowed view over a byte range of another [SeekableData].
 *
 * This is what lets a multi-GB video texture inside a package be *parsed*
 * (headers only) and *streamed* (payload) without ever materialising the
 * whole entry in a ByteArray.
 */
class SliceSeekableData(
    private val parent: SeekableData,
    private val start: Long,
    length: Long
) : SeekableData {

    private val span: Long = minOf(length, (parent.size - start).coerceAtLeast(0L))

    override val size: Long get() = span

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0) throw EOFException("bad slice read: $offset/$length")
        if (offset + length > span) throw EOFException("read past end of slice")
        return parent.readAt(start + offset, length)
    }
}

/**
 * Streams `[start, start + length)` of a [SeekableData] in bounded chunks.
 *
 * The export path uses this instead of handing a ByteArray to the saver, so a
 * 3 GB MP4 costs one chunk of heap instead of 3 GB. [onClose] releases whatever
 * the underlying source owns (a file channel, typically).
 */
class SliceInputStream(
    private val src: SeekableData,
    private val start: Long,
    private val length: Long,
    private val chunkSize: Int = DEFAULT_CHUNK_BYTES,
    private val onClose: () -> Unit = {}
) : InputStream() {

    init {
        require(start >= 0 && length >= 0 && start <= src.size && length <= src.size - start)
        require(chunkSize > 0)
    }

    private var pos: Long = start
    private var buf: ByteArray = EMPTY
    private var bufPos = 0
    private var bufLen = 0
    private var closed = false

    override fun read(): Int {
        if (!fill()) return -1
        return buf[bufPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, bufLen - bufPos)
        System.arraycopy(buf, bufPos, b, off, n)
        bufPos += n
        return n
    }

    override fun available(): Int = bufLen - bufPos

    override fun close() {
        if (closed) return
        closed = true
        buf = EMPTY
        bufPos = 0
        bufLen = 0
        runCatching { onClose() }
    }

    /** Pulls the next chunk into [buf]; false at end of the requested range. */
    private fun fill(): Boolean {
        check(!closed) { "Stream is closed" }
        if (bufPos < bufLen) return true
        val remaining = length - (pos - start)
        if (remaining <= 0) return false
        val want = minOf(chunkSize.toLong(), remaining).toInt()
        if (buf.isEmpty()) buf = ByteArray(want)
        src.readInto(pos, buf, want)
        bufPos = 0
        bufLen = want
        if (bufLen <= 0) return false
        pos += bufLen
        return true
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Chunk pulled per underlying read: large enough to keep syscalls cheap. */
        const val DEFAULT_CHUNK_BYTES = 512 * 1024
    }
}

/**
 * Copies [input] to [output] in bounded chunks and returns the byte count.
 *
 * This is the single write path shared by every streaming save route
 * (MediaStore / SAF / legacy), so the "no whole file in the heap" guarantee is
 * testable once, in pure JVM, without any Android dependency.
 */
fun copyStream(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = DEFAULT_COPY_BUFFER_BYTES
): Long {
    val buffer = ByteArray(bufferSize.coerceAtLeast(1))
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        total += read
    }
    return total
}

/** 512 KB: big enough for multi-GB videos, small enough to stay off the GC's radar. */
const val DEFAULT_COPY_BUFFER_BYTES = 512 * 1024

/**
 * Opens a read stream over `[offset, offset + length)` of [file].
 *
 * The file handle is opened lazily by the stream and released when it is closed,
 * so a multi-GB video export costs one chunk of heap plus one file descriptor.
 */
fun openFileRegion(file: File, offset: Long, length: Long): InputStream {
    val channel = RandomAccessFile(file, "r").channel
    try {
        return SliceInputStream(ChannelSeekableData(channel), offset, length) {
            channel.close()
        }
    } catch (e: Throwable) {
        channel.close()
        throw e
    }
}

/**
 * Little-endian sequential reader with a movable cursor.
 * Mirrors the BinaryReader extension methods used by the original RePKG.
 */
class LeReader(private val src: SeekableData) {
    var position: Long = 0
        private set

    private fun readRaw(n: Int): ByteArray {
        val bytes = src.readAt(position, n)
        position += n
        return bytes
    }

    /** Read exactly [n] bytes at the cursor and advance it. */
    fun readBytes(n: Int): ByteArray = readRaw(n)

    /** Read a signed 32-bit little-endian integer. */
    fun readInt32(): Int {
        val b = readRaw(4)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Read an unsigned 32-bit little-endian value as a long. */
    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    /**
     * String prefixed by a signed 32-bit length (see ReadStringI32Size).
     */
    fun readStringI32Size(maxLength: Int = -1): String {
        var size = readInt32()
        if (size < 0) throw FormatException("String length cannot be negative")
        if (maxLength > -1 && size > maxLength) size = maxLength
        val bytes = readRaw(size)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * NUL-terminated string (see ReadNString). Reads byte by byte; only used
     * for tiny magic identifiers.
     */
    fun readNString(maxLength: Int = -1): String {
        val sb = StringBuilder(if (maxLength > 0) maxLength else 16)
        while (true) {
            val b = readRaw(1)[0]
            if (b.toInt() == 0) break
            sb.append(b.toInt().and(0xFF).toChar())
            if (maxLength > -1 && sb.length >= maxLength) break
        }
        return sb.toString()
    }
}

class FormatException(message: String) : RuntimeException(message)

/** Convenience to build a little-endian int in tests/builders. */
internal fun Int.toLeBytes(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
