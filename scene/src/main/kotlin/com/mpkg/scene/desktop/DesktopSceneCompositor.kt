package com.mpkg.scene.desktop

import com.mpkg.scene.core.*
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Static scene rendering. Run on a worker thread; the caller owns the returned image. */
object DesktopSceneCompositor {
    /** Metadata-only routing. Videos and single-image packages stay on the existing extractor. */
    fun findScene(file: File, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): SceneExtractResult? =
        file.inputStream().channel.use { channel ->
            val source = ChannelSeekableData(channel)
            val archive = PkgReader.read(source)
            if (archive.entries.none { it.path.equals("scene.json", true) }) return@use null
            val mobile = archive.magic.startsWith("PKGM")
            for (entry in archive.entries) {
                checkCancelled()
                progress(0, 0, "识别场景媒体")
                if (entry.extension == ".mp4") return@use null
                if (entry.extension == ".tex" && runCatching {
                    TexAssetDecoder.peekSource(SliceSeekableData(source, entry.dataOffset, entry.length), mobile)?.isVideo
                }.getOrNull() == true) return@use null
            }
            SceneExtractor.analyze(source, DesktopLz4, SceneProgress(progress), deferPayloads = true)
                ?.takeIf { it.info.layers.size >= 2 }
        }

    data class Options(
        val scale: Float = 1f,
        val allVisible: Boolean = false,
        val maxWorkingBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 2, 512L * 1024 * 1024)
    )

    fun analyze(file: File, budget: Long = Options().maxWorkingBytes,
                progress: (Int, Int, String) -> Unit = { _, _, _ -> }): WallpaperAnalysis =
        file.inputStream().channel.use { channel ->
            WallpaperAnalyzer.analyze(ChannelSeekableData(channel), DesktopLz4, budget) { n, total, message ->
                checkCancelled()
                progress(n, total, message)
            }
        }

    /** Keep source unchanged between analyze and render: metadata contains byte offsets. */
    fun renderFile(file: File, scene: SceneExtractResult, options: Options = Options(),
                   progress: (Int, Int, String) -> Unit = { _, _, _ -> }): BufferedImage =
        file.inputStream().channel.use { channel ->
            val source = ChannelSeekableData(channel)
            render(scene, options, { asset ->
                SceneExtractor.loadAsset(source, asset, DesktopLz4, options.maxWorkingBytes / 2)
            }, progress)
        }

    fun render(scene: SceneExtractResult, options: Options = Options(),
               loadAsset: (SceneAsset) -> SceneAsset = { it },
               progress: (Int, Int, String) -> Unit = { _, _, _ -> }): BufferedImage {
        require(options.scale.isFinite() && options.scale > 0f && options.scale <= 1f)
        require(scene.info.canvasWidth > 0 && scene.info.canvasHeight > 0)
        val (width, height) = SceneLayout.canvasSize(scene.info.canvasWidth, scene.info.canvasHeight, options.scale)
        val canvasBytes = width.toLong() * height * 4
        require(canvasBytes < options.maxWorkingBytes / 2) { "画布超出内存预算，请降低 scale" }
        val layers = scene.info.layers.filter { options.allVisible || it.visible }
        require(layers.isNotEmpty()) { "没有可合成图层" }
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.composite = AlphaComposite.SrcOver
            if (scene.info.backgroundArgb != 0) {
                graphics.color = java.awt.Color(scene.info.backgroundArgb, true)
                graphics.fillRect(0, 0, width, height)
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            layers.forEachIndexed { index, layer ->
                checkCancelled()
                progress(index, layers.size, layer.objectName)
                val metadata = scene.layerAssets[layer.textureName] ?: error("图层纹理缺失：${layer.objectName}")
                val asset = loadAsset(metadata)
                val image = decode(asset, options.maxWorkingBytes - canvasBytes)
                try {
                    for (effect in layer.opacityMasks) {
                        val mask = effect.texture?.let { name ->
                            val meta = scene.maskAssets[name] ?: error("透明度遮罩缺失：$name")
                            decode(loadAsset(meta), options.maxWorkingBytes - canvasBytes - image.width.toLong() * image.height * 4)
                        }
                        try { applyOpacityMask(image, mask, effect.opacity) } finally { mask?.flush() }
                    }
                    applyTint(image, layer.tint)
                    graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity)
                    require(listOf(layer.originX, layer.originY, layer.scaleX, layer.scaleY, layer.angleZDeg).all { it.isFinite() })
                    val p = SceneLayout.placement(scene.info.canvasWidth, scene.info.canvasHeight, layer, options.scale)
                    // Match Android: map image to its rectangle, then rotate by -angle about its center.
                    val transform = AffineTransform.getRotateInstance(
                        Math.toRadians(-layer.angleZDeg.toDouble()), p.centerX.toDouble(), p.centerY.toDouble())
                    transform.translate(p.left.toDouble(), p.top.toDouble())
                    transform.scale(p.width.toDouble() / image.width, p.height.toDouble() / image.height)
                    graphics.drawImage(image, transform, null)
                } finally { image.flush() }
                progress(index + 1, layers.size, layer.objectName)
            }
            return canvas
        } catch (e: Throwable) {
            canvas.flush()
            throw e
        } finally { graphics.dispose() }
    }

    /** Same red-channel alpha multiplication as effects/opacity.frag, with bilinear UV sampling. */
    internal fun applyOpacityMask(image: BufferedImage, mask: BufferedImage?, opacity: Float) {
        val row = IntArray(image.width)
        fun red(x: Int, y: Int): Float = ((mask!!.getRGB(x.coerceIn(0, mask.width - 1), y.coerceIn(0, mask.height - 1)) ushr 16) and 255) / 255f
        for (y in 0 until image.height) {
            checkCancelled()
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
            for (x in row.indices) {
                val factor = if (mask == null) 1f else {
                    val u = (x + .5f) * mask.width / image.width - .5f
                    val v = (y + .5f) * mask.height / image.height - .5f
                    val x0 = kotlin.math.floor(u).toInt(); val y0 = kotlin.math.floor(v).toInt()
                    val dx = u - x0; val dy = v - y0
                    (red(x0, y0) * (1 - dx) + red(x0 + 1, y0) * dx) * (1 - dy) +
                        (red(x0, y0 + 1) * (1 - dx) + red(x0 + 1, y0 + 1) * dx) * dy
                }
                val a = ((row[x] ushr 24) * factor * opacity).toInt().coerceIn(0, 255)
                row[x] = (row[x] and 0xffffff) or (a shl 24)
            }
            image.setRGB(0, y, image.width, 1, row, 0, image.width)
        }
    }

    private fun applyTint(image: BufferedImage, tint: List<Float>) {
        if (tint == listOf(1f, 1f, 1f)) return
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            checkCancelled()
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
            for (x in row.indices) {
                val c = row[x]
                row[x] = (c and 0xff000000.toInt()) or ((((c ushr 16) and 255) * tint[0]).toInt() shl 16) or
                    ((((c ushr 8) and 255) * tint[1]).toInt() shl 8) or ((c and 255) * tint[2]).toInt()
            }
            image.setRGB(0, y, image.width, 1, row, 0, image.width)
        }
    }

    /** PNG is streamed to disk; an existing destination is never silently overwritten. */
    fun writePng(image: BufferedImage, destination: File) {
        val target = destination.toPath().toAbsolutePath()
        require(!Files.exists(target)) { "目标文件已存在：$destination" }
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "wallpaper-", ".png")
        try {
            checkCancelled()
            Files.newOutputStream(temp).use { check(ImageIO.write(image, "png", it)) { "PNG encoder unavailable" } }
            checkCancelled()
            Files.move(temp, target)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun decode(asset: SceneAsset, budget: Long): BufferedImage {
        fun checkSize(w: Int, h: Int, extra: Long = 0) {
            require(w > 0 && h > 0 && w.toLong() * h <= (budget - extra) / 16) {
                "图层超出内存预算：${asset.fileName}"
            }
        }
        asset.bytes?.let { bytes ->
            MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                val readers = ImageIO.getImageReaders(input)
                check(readers.hasNext()) { "JRE 无法读取图片格式：${asset.fileName}" }
                val reader = readers.next()
                try {
                    reader.input = input
                    checkSize(reader.getWidth(0), reader.getHeight(0), bytes.size.toLong())
                    val decoded = reader.read(0) ?: error("图层图片解码失败")
                    if (decoded.type == BufferedImage.TYPE_INT_ARGB) return decoded
                    val argb = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB)
                    val g = argb.createGraphics()
                    try { g.composite = AlphaComposite.Src; g.drawImage(decoded, 0, 0, null) }
                    finally { g.dispose(); decoded.flush() }
                    return argb
                } finally { reader.dispose() }
            }
        }
        checkSize(asset.width, asset.height,
            (asset.rgba?.size ?: asset.compressedBytes?.size ?: 0).toLong())
        val pixels = when {
            asset.rgba != null -> {
                val rgba = asset.rgba
                val count = Math.multiplyExact(asset.width, asset.height)
                require(rgba.size.toLong() >= count.toLong() * 4) { "RGBA 数据截断" }
                IntArray(count) { i ->
                    val p = i * 4
                    ((rgba[p + 3].toInt() and 255) shl 24) or ((rgba[p].toInt() and 255) shl 16) or
                        ((rgba[p + 1].toInt() and 255) shl 8) or (rgba[p + 2].toInt() and 255)
                }
            }
            asset.compressedBytes != null -> Etc2CpuDecoder.decodeArgb(asset.compressedBytes, asset.width, asset.height)
            else -> error("图层没有可解码的像素：${asset.fileName}")
        }
        return BufferedImage(asset.width, asset.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("合成已取消")
    }
}
