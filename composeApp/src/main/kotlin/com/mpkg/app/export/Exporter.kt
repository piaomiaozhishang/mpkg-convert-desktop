package com.mpkg.app.export

import com.mpkg.app.extract.OutputItem
import com.mpkg.app.extract.OutputPayload
import com.mpkg.app.image.PngIo
import com.mpkg.app.util.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** 同名文件覆盖策略（任务卡 T2.5）。 */
enum class OverwritePolicy {
    /** 已存在则跳过。 */
    SKIP,

    /** 已存在则覆盖。 */
    OVERWRITE,

    /** 已存在则加序号另存。 */
    RENAME
}

/**
 * 导出器（任务卡 T2.5）。
 *
 * 三条落盘路径分别对应 [OutputPayload] 的三种形态，其中流式复制用 1 MiB 缓冲，
 * 大文件（几十上百 MB 的视频）不会被整体读进内存。
 */
object Exporter {

    private const val BUFFER_SIZE = 1 shl 20

    /** 计算实际写入路径；返回 null 表示按策略跳过。 */
    fun resolveTarget(dir: File, fileName: String, policy: OverwritePolicy): File? {
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("无法创建输出目录: $dir")
        }
        val direct = File(dir, fileName)
        if (!direct.exists()) return direct
        return when (policy) {
            OverwritePolicy.OVERWRITE -> direct
            OverwritePolicy.SKIP -> null
            OverwritePolicy.RENAME -> {
                val base = fileName.substringBeforeLast('.', fileName)
                val ext = fileName.substringAfterLast('.', "")
                var index = 1
                var candidate = File(dir, if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext")
                while (candidate.exists()) {
                    index++
                    candidate = File(dir, if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext")
                }
                candidate
            }
        }
    }

    /**
     * 写出一个产物，返回实际写入的文件；按策略跳过时返回 null。
     */
    fun write(item: OutputItem, dir: File, policy: OverwritePolicy,
              progress: (Int, Int, String) -> Unit = { _, _, _ -> },
              checkActive: () -> Unit = {}): File? {
        if (item.payload is OutputPayload.Scene) {
            return SceneExporter.write(item, dir, policy, progress, checkActive)
        }
        val target = resolveTarget(dir, item.suggestedName, policy) ?: return null
        when (val payload = item.payload) {
            is OutputPayload.Bytes -> {
                target.outputStream().buffered(BUFFER_SIZE).use { it.write(payload.data) }
            }
            is OutputPayload.StreamSlice -> streamCopy(payload, target)
            is OutputPayload.Pixels -> PngIo.writeArgb(payload.argb, payload.width, payload.height, target)
            is OutputPayload.Scene -> error("Scene output must use the scene exporter")
        }
        AppLog.i("已写出 ${target.name} (${target.length()} 字节)")
        return target
    }

    /** 把包内 [OutputPayload.StreamSlice] 指定的字节区间分批复制到目标文件。 */
    fun streamCopy(slice: OutputPayload.StreamSlice, target: File) {
        FileChannel.open(slice.source.toPath(), StandardOpenOption.READ).use { src ->
            RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                val dst = raf.channel
                var position = slice.offset
                var remaining = slice.length
                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                while (remaining > 0) {
                    buffer.clear()
                    buffer.limit(minOf(BUFFER_SIZE.toLong(), remaining).toInt())
                    val read = src.read(buffer, position)
                    if (read <= 0) throw java.io.EOFException("读取包内数据提前结束于偏移 $position")
                    buffer.flip()
                    while (buffer.hasRemaining()) dst.write(buffer)
                    position += read
                    remaining -= read
                }
                raf.fd.sync()
            }
        }
    }
}
