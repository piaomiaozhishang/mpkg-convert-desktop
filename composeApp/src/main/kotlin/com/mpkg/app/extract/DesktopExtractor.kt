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
import com.mpkg.scene.core.SceneExtractResult
import com.mpkg.scene.desktop.DesktopSceneCompositor

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

    /** Only metadata is retained; the exporter decodes and composites layers one at a time. */
    class Scene(val source: File, val scene: SceneExtractResult) : OutputPayload
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
            is OutputPayload.Scene -> "png"
        }

    fun byteSize(): Long = when (payload) {
        is OutputPayload.Bytes -> payload.data.size.toLong()
        is OutputPayload.StreamSlice -> payload.length
        is OutputPayload.Pixels -> payload.width.toLong() * payload.height * 4
        is OutputPayload.Scene -> payload.scene.info.canvasWidth.toLong() * payload.scene.info.canvasHeight * 4
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

    /** 只用来读 `.tex` 头部；视频纹理的头通常远小于此。 */
    private const val TEX_HEADER_PREFIX = 64 * 1024

    /**
     * 打开源文件。失败时统一包装成「读取源文件失败」，让 [com.mpkg.app.errors.ErrorMessages]
     * 能把**读侧**故障和写盘故障区分开（审查报告 M5）。
     */
    private fun openSource(file: File): java.nio.channels.FileChannel = try {
        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)
    } catch (e: java.io.IOException) {
        throw java.io.IOException("读取源文件失败: ${file.absolutePath}", e)
    }

    /**
     * 分析一个 `.mpkg` / `.pkg`。
     *
     * @param file     包文件
     * @param progress 阶段进度回调（0..1 + 阶段文案）
     */
    fun analyzePackage(file: File, progress: StageProgress? = null): PackageAnalysis {
        progress?.onStage(0.02f, "打开文件")
        openSource(file).use { channel ->
            val src = com.doubao.repkg.core.ChannelSeekableData(channel)
            // PkgReader.read 只读 TOC（小前缀），不会把数据区拉进内存。
            val archive: PkgArchive = PkgReader.read(src)
            progress?.onStage(0.08f, "解析包结构（${archive.entries.size} 个条目）")

            val scene = DesktopSceneCompositor.findScene(file) { n, total, message ->
                progress?.onStage(if (total > 0) 0.10f + 0.3f * n / total else 0.10f, message)
            }
            if (scene != null) {
                return PackageAnalysis(
                    archive.magic, archive.entries.size, false,
                    OutputItem(AssetKind.IMAGE, file.nameWithoutExtension + "_合成.png", "scene.json",
                        OutputPayload.Scene(file, scene), true,
                        "静态合成 · ${scene.info.visibleLayerCount} 个可见图层 · " +
                            "${scene.info.layers.filter { it.visible }.sumOf { it.opacityMasks.size }} 个透明度遮罩；输出 PNG" +
                            scene.info.notes.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "；").orEmpty()),
                    null, archive.entries
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
            // 视频由 core 描述成「包内字节区间」，这里转成 StreamSlice，导出时才真正读盘。
            val main = result.main?.let { toOutputItem(it, file, progress, 0.50f, 0.88f) }
            progress?.onStage(0.92f, "准备输出")
            val preview = result.preview?.let { toOutputItem(it, file) }
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

    /**
     * 分析一个独立的 `.tex` 文件。
     *
     * 先只读头部前缀：未压缩的视频纹理直接按字节区间交给导出器流式复制，
     * 只有确需解码（含 LZ4 压缩的）纹理才整文件读入。
     */
    fun analyzeTexFile(file: File): TexFileAnalysis {
        val size = file.length()
        if (size > TEX_HEADER_PREFIX) {
            openSource(file).use { channel ->
                val src = com.doubao.repkg.core.ChannelSeekableData(channel)
                val head = src.readAt(0, TEX_HEADER_PREFIX)
                val slice = try {
                    TexAssetDecoder.peekMipSlice(head, mobileLayout = false)
                } catch (e: Exception) {
                    null
                }
                if (slice != null && slice.isVideo && !slice.isLz4) {
                    val length = minOf(slice.length, size - slice.offset)
                    if (length > 0 && slice.offset + 12 <= size &&
                        TexAssetDecoder.isMp4Header(src.readAt(slice.offset, 12))
                    ) {
                        return TexFileAnalysis(
                            isVideo = true,
                            main = OutputItem(
                                kind = AssetKind.VIDEO,
                                suggestedName = file.nameWithoutExtension + ".mp4",
                                sourceEntry = file.name,
                                payload = OutputPayload.StreamSlice(file, slice.offset, length),
                                isPrimary = true,
                                note = "视频（字节导出）"
                            )
                        )
                    }
                }
            }
        }

        // 静态纹理 / LZ4 压缩的视频：必须完整读入才能解码。
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw java.io.IOException("读取源文件失败: ${file.absolutePath}", e)
        }
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

    private fun toOutputItem(
        asset: com.doubao.repkg.core.ExtractedAsset,
        source: File
    ): OutputItem {
        val payload = when {
            // 视频：只记录字节区间，导出时 seek + 分批复制（红线 #2）。
            asset.sliceOffset != null -> OutputPayload.StreamSlice(source, asset.sliceOffset!!, asset.sliceLength)
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

    /**
     * 带阶段进度的版本。
     *
     * 解码耗时集中在这里，是进度条最需要平滑的一段；所有载荷形态都要发前后两条进度，
     * 否则非 ETC2 的主图（RGBA / DXT）会让进度从 12% 直接跳到 92%。
     */
    private fun toOutputItem(
        asset: com.doubao.repkg.core.ExtractedAsset,
        source: File,
        progress: StageProgress?,
        from: Float,
        to: Float
    ): OutputItem {
        val stage = when {
            asset.sliceOffset != null -> "准备视频字节（${asset.sliceLength / 1024 / 1024} MB）"
            asset.bytes != null -> "取出已编码内容"
            asset.compressedBytes != null ->
                "解码 ETC2_RGBA8 纹理 ${asset.pixelWidth}x${asset.pixelHeight}"
            asset.pixelsRgba != null ->
                "转换像素 ${asset.pixelWidth}x${asset.pixelHeight}"
            else -> "解码纹理"
        }
        progress?.onStage(from, stage)
        val item = toOutputItem(asset, source)
        progress?.onStage(to, "内容就绪")
        return item
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
