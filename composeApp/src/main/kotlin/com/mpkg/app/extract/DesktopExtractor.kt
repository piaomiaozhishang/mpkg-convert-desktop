package com.mpkg.app.extract

import com.doubao.repkg.core.AssetKind
import com.doubao.repkg.core.Extractor
import com.doubao.repkg.core.PkgArchive
import com.doubao.repkg.core.PkgEntry
import com.doubao.repkg.core.PkgReader
import com.doubao.repkg.core.TexAsset
import com.doubao.repkg.core.TexAssetDecoder
import com.mpkg.app.image.PngIo
import java.io.File

/**
 * 一个待导出产物的载荷。
 *
 * 三种形态对应三种落盘方式：
 * - [Bytes]：字节原样写出（MP4 / 内嵌 PNG/JPEG/GIF）——红线 #1「视频字节导出，绝不重编码」；
 * - [StreamSlice]：只记录包内偏移与长度，导出时 seek + 分批复制，**不把大文件读进内存**（红线 #2）；
 * - [Pixels]：解码后的 ARGB 像素，需要编码成 PNG。
 */
sealed interface OutputPayload {
    class Bytes(val data: ByteArray) : OutputPayload

    class StreamSlice(val source: File, val offset: Long, val length: Long) : OutputPayload

    class Pixels(val argb: IntArray, val width: Int, val height: Int) : OutputPayload
}

/** 一个可导出的产物（主图或预览图）。 */
class OutputItem(
    val kind: AssetKind,
    val suggestedName: String,
    val sourceEntry: String,
    val payload: OutputPayload,
    val isPrimary: Boolean,
    val note: String?
) {
    /** 建议扩展名（小写，不含点）。 */
    val extension: String
        get() = when (payload) {
            is OutputPayload.StreamSlice -> "mp4"
            is OutputPayload.Bytes -> suggestedName.substringAfterLast('.', "bin").lowercase()
            is OutputPayload.Pixels -> "png"
        }

    fun byteSize(): Long = when (payload) {
        is OutputPayload.Bytes -> payload.data.size.toLong()
        is OutputPayload.StreamSlice -> payload.length
        is OutputPayload.Pixels -> payload.width.toLong() * payload.height * 4
    }
}

/** 一个壁纸包的分析结果。 */
class PackageAnalysis(
    val magic: String,
    val entryCount: Int,
    val isVideo: Boolean,
    val main: OutputItem?,
    val preview: OutputItem?,
    val entries: List<PkgEntry>
)

/** 一个独立 `.tex` 文件的分析结果（CLI 直接解纹理时使用）。 */
class TexFileAnalysis(
    val isVideo: Boolean,
    val main: OutputItem?
)

/**
 * 阶段级进度回调。
 *
 * core 的解码是单次不可中断调用，拿不到块级进度，因此按**文件阶段**汇报
 * （任务卡 T2.4 的注意事项明确允许「按文件阶段计算」），UI 侧再做平滑动画，
 * 保证不会出现 0% 直接跳 100%。
 */
fun interface StageProgress {
    fun onStage(fraction: Float, stage: String)
}

/**
 * 桌面端解码适配层：壳层与 core 之间的唯一解码入口。
 *
 * 职责边界（见《02 · 架构与目录结构》第 1 节）：
 * - 位级格式逻辑全部在 core；本层只做「解码器选择 + 输出形态转换」；
 * - ETC2_RGBA8（Format = 5）在 Android 上交给 GPU，桌面端改用 core 的纯 CPU 解码器
 *   [com.doubao.repkg.core.Etc2CpuDecoder]；
 * - `Etc2CpuDecoder` 使用共享 scratch 数组、源码注明**非可重入**，因此这里用锁串行化。
 */
object DesktopExtractor {

    /** 超过该体积的包内 `.mp4` 走 seek + 流式复制，不整读内存。 */
    private const val STREAM_COPY_THRESHOLD = 32L * 1024 * 1024

    private val plainImageExtensions =
        setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tga", ".dds")

    /**
     * 分析一个 `.mpkg` / `.pkg`。
     *
     * @param file     包文件
     * @param progress 阶段进度回调（0..1 + 阶段文案）
     */
    fun analyzePackage(file: File, progress: StageProgress? = null): PackageAnalysis {
        progress?.onStage(0.02f, "打开文件")
        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
            val src = com.doubao.repkg.core.ChannelSeekableData(channel)
            // PkgReader.read 只读 TOC（小前缀），不会把数据区拉进内存。
            val archive: PkgArchive = PkgReader.read(src)
            progress?.onStage(0.08f, "解析包结构（${archive.entries.size} 个条目）")

            val largeMp4 = archive.entries
                .filter { it.extension == ".mp4" }
                .maxByOrNull { it.length }
            if (largeMp4 != null && largeMp4.length >= STREAM_COPY_THRESHOLD) {
                progress?.onStage(0.85f, "大视频：按包内偏移流式导出")
                return PackageAnalysis(
                    magic = archive.magic,
                    entryCount = archive.entries.size,
                    isVideo = true,
                    main = streamSliceFor(file, largeMp4),
                    preview = findPlainPreview(src, archive),
                    entries = archive.entries
                )
            }

            progress?.onStage(0.12f, "扫描条目并挑选主图")
            val result = Extractor.analyze(src, CoreDecoding.lz4) { current, total, path ->
                val fraction = if (total <= 0) 0.5f else current.toFloat() / total
                progress?.onStage(0.12f + 0.33f * fraction, "扫描条目 $current/$total")
                if (com.mpkg.app.util.AppLog.debugEnabled) {
                    com.mpkg.app.util.AppLog.d("扫描 $current/$total $path")
                }
            }
            progress?.onStage(0.50f, if (result.main?.kind == AssetKind.VIDEO) "提取视频字节" else "解码纹理")
            val main = result.main?.let { toOutputItem(it, progress, 0.50f, 0.88f) }
            progress?.onStage(0.92f, "准备输出")
            val preview = result.preview?.let { toOutputItem(it) }
            progress?.onStage(0.98f, "完成")
            return PackageAnalysis(
                magic = archive.magic,
                entryCount = archive.entries.size,
                isVideo = result.isVideo,
                main = main,
                preview = preview,
                entries = archive.entries
            )
        }
    }

    /** 分析一个独立的 `.tex` 文件（整文件读入：纹理本身不是几百 MB 的视频容器）。 */
    fun analyzeTexFile(file: File): TexFileAnalysis {
        val bytes = file.readBytes()
        val asset = TexAssetDecoder.decode(bytes, CoreDecoding.lz4, mobileLayout = false)
        if (asset == null) {
            // 能读出头部却解不出内容时，给出「格式不支持」而不是笼统的「没有内容」。
            val peek = TexAssetDecoder.peek(bytes, mobileLayout = false)
            if (peek != null) {
                throw com.doubao.repkg.core.FormatException("unsupported tex format: ${peek.texFormat}")
            }
            return TexFileAnalysis(isVideo = false, main = null)
        }
        return when (asset) {
            is TexAsset.Video -> TexFileAnalysis(
                isVideo = true,
                main = OutputItem(
                    kind = AssetKind.VIDEO,
                    suggestedName = file.nameWithoutExtension + ".mp4",
                    sourceEntry = file.name,
                    payload = OutputPayload.Bytes(asset.bytes),
                    isPrimary = true,
                    note = "视频（字节导出）"
                )
            )
            is TexAsset.Image -> TexFileAnalysis(
                isVideo = false,
                main = toOutputItem(file.name, asset, primary = true, note = "主图（完整分辨率）")
            )
        }
    }

    /** 只读包内 preview.* 条目的字节（不解码纹理、不加载整个包）。 */
    private fun findPlainPreview(
        src: com.doubao.repkg.core.SeekableData,
        archive: PkgArchive
    ): OutputItem? {
        val entry = archive.entries.firstOrNull {
            it.path.contains("preview", ignoreCase = true) && it.extension in plainImageExtensions
        } ?: return null
        return try {
            OutputItem(
                kind = AssetKind.IMAGE,
                suggestedName = entry.fileName,
                sourceEntry = entry.path,
                payload = OutputPayload.Bytes(PkgReader.readEntryBytes(src, entry)),
                isPrimary = false,
                note = "预览图"
            )
        } catch (e: Exception) {
            com.mpkg.app.util.AppLog.w("读取预览图失败: ${e.message}")
            null
        }
    }

    private fun streamSliceFor(file: File, entry: PkgEntry): OutputItem = OutputItem(
        kind = AssetKind.VIDEO,
        suggestedName = entry.fileName,
        sourceEntry = entry.path,
        payload = OutputPayload.StreamSlice(file, entry.dataOffset, entry.length),
        isPrimary = true,
        note = "视频（包内流式字节导出）"
    )

    private fun toOutputItem(asset: com.doubao.repkg.core.ExtractedAsset): OutputItem {
        val payload = when {
            asset.bytes != null -> OutputPayload.Bytes(asset.bytes!!)
            asset.pixelsRgba != null -> OutputPayload.Pixels(
                argb = PngIo.rgbaToArgb(asset.pixelsRgba!!, asset.pixelWidth, asset.pixelHeight),
                width = asset.pixelWidth,
                height = asset.pixelHeight
            )
            asset.compressedBytes != null -> {
                val argb = CoreDecoding.decodeEtc2(asset.compressedBytes!!, asset.pixelWidth, asset.pixelHeight)
                OutputPayload.Pixels(argb, asset.pixelWidth, asset.pixelHeight)
            }
            else -> throw com.doubao.repkg.core.FormatException("asset has no payload: ${asset.sourceEntry}")
        }
        return OutputItem(asset.kind, asset.fileName, asset.sourceEntry, payload, asset.isPrimary, asset.note)
    }

    /** 带阶段进度的版本：解码耗时集中在这里，是进度条最需要平滑的一段。 */
    private fun toOutputItem(
        asset: com.doubao.repkg.core.ExtractedAsset,
        progress: StageProgress?,
        from: Float,
        to: Float
    ): OutputItem {
        if (asset.compressedBytes == null) return toOutputItem(asset)
        val width = asset.pixelWidth
        val height = asset.pixelHeight
        progress?.onStage(from, "解码 ETC2_RGBA8 纹理 ${width}x$height")
        val argb = CoreDecoding.decodeEtc2(asset.compressedBytes!!, width, height)
        progress?.onStage(to, "纹理解码完成")
        return OutputItem(
            asset.kind, asset.fileName, asset.sourceEntry,
            OutputPayload.Pixels(argb, width, height), asset.isPrimary, asset.note
        )
    }

    private fun toOutputItem(
        entryName: String,
        asset: TexAsset.Image,
        primary: Boolean,
        note: String
    ): OutputItem {
        val payload = when {
            asset.encodedBytes != null -> OutputPayload.Bytes(asset.encodedBytes!!)
            asset.compressedBytes != null -> OutputPayload.Pixels(
                argb = CoreDecoding.decodeEtc2(asset.compressedBytes!!, asset.width, asset.height),
                width = asset.width,
                height = asset.height
            )
            asset.rgba != null -> OutputPayload.Pixels(
                argb = PngIo.rgbaToArgb(asset.rgba!!, asset.width, asset.height),
                width = asset.width,
                height = asset.height
            )
            else -> throw com.doubao.repkg.core.FormatException("tex image has no payload: $entryName")
        }
        val base = entryName.substringBeforeLast('.', entryName)
        val name = if (payload is OutputPayload.Bytes) {
            "$base.${asset.extension}"
        } else {
            "$base.png"
        }
        return OutputItem(AssetKind.IMAGE, name, entryName, payload, primary, note)
    }
}
