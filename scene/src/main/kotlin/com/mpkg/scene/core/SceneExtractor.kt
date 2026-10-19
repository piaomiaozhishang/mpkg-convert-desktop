package com.mpkg.scene.core

/**
 * 场景包提取：把 scene.json 引用的艺术纹理 + 包里其余艺术纹理全部解码出来。
 *
 * 与 [Extractor]（只挑一张主图）不同，这里面向场景壁纸：
 *  - 图层纹理按 scene.json 解析结果逐个解码（合成用）；
 *  - 其余非遮罩、非特效目录的艺术纹理也一并导出（被隐藏/备用的角色立绘），
 *    标记 [SceneAsset.isSceneLayer] = false，只进"导出全部原图"，不参与合成。
 */

/** 一张解码后的艺术纹理（编码字节原样保留 / 原始像素 / ETC2 块三选一）。 */
data class SceneAsset(
    /** 建议文件名（含扩展名），如 "fondo zero.png"。 */
    val fileName: String,
    /** 纹理名（材质 textures 引用用），如 "fondo zero"。 */
    val textureName: String,
    /** 来源 TOC 条目，如 "materials/fondo zero.tex"。 */
    val sourceEntry: String,
    val width: Int,
    val height: Int,
    /** imageFormat ∈ {13,2,25} 时的完整图片文件字节（PNG/JPEG/GIF），原样导出。 */
    val bytes: ByteArray?,
    /** RAW 像素（RGBA8888 / 裁剪后）。 */
    val rgba: ByteArray?,
    /** ETC2_RGBA8 压缩块（texFormat=5），需要 GPU/CPU 解码。 */
    val compressedBytes: ByteArray?,
    val texFormat: Int,
    val imageFormat: Int,
    val extension: String,
    /** true = scene.json 图层直接引用（参与合成）。 */
    val isSceneLayer: Boolean,
    /** Deferred source range; payloads are loaded one layer at a time. */
    val source: SceneAssetSource? = null
) {
    override fun equals(other: Any?): Boolean =
        other is SceneAsset && other.sourceEntry == sourceEntry

    override fun hashCode(): Int = sourceEntry.hashCode()
}

data class SceneAssetSource(val offset: Long, val length: Long, val mobileLayout: Boolean)

data class SceneExtractResult(
    val info: SceneInfo,
    /** 图层纹理（按数组序）在前，其余艺术纹理在后。 */
    val assets: List<SceneAsset>,
    /** textureName -> asset，合成时按图层查找。 */
    val layerAssets: Map<String, SceneAsset>,
    val maskAssets: Map<String, SceneAsset> = emptyMap()
)

/** 进度回调：current/total/message。 */
fun interface SceneProgress {
    fun onProgress(current: Int, total: Int, message: String)
}

object SceneExtractor {

    fun loadAsset(
        src: SeekableData, asset: SceneAsset, lz4: Lz4BlockDecompressor?,
        maxWorkingBytes: Long = Long.MAX_VALUE
    ): SceneAsset {
        val source = asset.source ?: return asset
        val decoded = TexAssetDecoder.decodeSource(
            SliceSeekableData(src, source.offset, source.length), lz4,
            source.mobileLayout, maxWorkingBytes
        ) as? TexAsset.Image ?: error("无法解码图层：${asset.fileName}")
        return asset.copy(
            width = decoded.width, height = decoded.height,
            bytes = decoded.encodedBytes, rgba = decoded.rgba, compressedBytes = decoded.compressedBytes,
            extension = decoded.extension, fileName = asset.textureName + "." + decoded.extension,
            source = null
        )
    }

    /** 没有 scene.json 时返回 null（调用方回退到普通静态/视频流程）。 */
    fun analyze(
        src: SeekableData,
        lz4: Lz4BlockDecompressor?,
        progress: SceneProgress? = null,
        deferPayloads: Boolean = false
    ): SceneExtractResult? {
        val archive = PkgReader.read(src)
        val sceneEntry = archive.entries.firstOrNull {
            it.path.equals("scene.json", ignoreCase = true)
        } ?: return null

        progress?.onProgress(0, archive.entries.size, "正在读取场景定义…")
        val sceneBytes = PkgReader.readEntryBytes(src, sceneEntry)
        val entryReader: (String) -> ByteArray? = { path ->
            archive.entries.firstOrNull { it.path == path }
                ?.let { PkgReader.readEntryBytes(src, it) }
        }
        val info = ScenePackage.parse(sceneBytes, entryReader) ?: return null

        val layerTextureNames = info.layers.mapNotNull { it.textureName }.toSet()
        val maskNames = info.layers.flatMap { it.opacityMasks }.mapNotNull { it.texture }.toSet()
        fun key(path: String) = path.replace('\\', '/').removePrefix("materials/").removeSuffix(".tex").lowercase()

        // 艺术纹理 = materials/*.tex，排除 masks/（特效遮罩）与 effects/（特效资产，如水波法线贴图）
        val artEntries = archive.entries.filter { e ->
            e.extension == ".tex" &&
            e.path.startsWith("materials/", true) &&
                ((!e.path.contains("/masks/") && !e.path.contains("/effects/")) ||
                    maskNames.any { key(it) == key(e.path) } || layerTextureNames.any { key(it) == key(e.path) })
        }

        val layerAssets = LinkedHashMap<String, SceneAsset>()
        val maskAssets = LinkedHashMap<String, SceneAsset>()
        val extraAssets = ArrayList<SceneAsset>()
        var done = 0
        for (entry in artEntries) {
            done++
            progress?.onProgress(done, artEntries.size, entry.path)
            val base = entry.path.removePrefix("materials/").removeSuffix(".tex")
            val layerRefs = layerTextureNames.filter { key(it) == key(entry.path) }
            val maskRefs = maskNames.filter { key(it) == key(entry.path) }
            val mobile = archive.magic.startsWith("PKGM")
            val range = SliceSeekableData(src, entry.dataOffset, entry.length)
            val peek = try { TexAssetDecoder.peekSource(range, mobile) } catch (_: Exception) { null }
                ?: continue
            if (peek.isVideo) continue
            val asset = if (deferPayloads) null else try {
                TexAssetDecoder.decodeSource(range, lz4, mobile) as? TexAsset.Image
            } catch (_: Exception) { null }
            if (!deferPayloads && asset == null) continue
            val extension = asset?.extension ?: TexAssetDecoder.imageExtension(peek.imageFormat)
            val sceneAsset = SceneAsset(
                fileName = base + "." + extension,
                textureName = base,
                sourceEntry = entry.path,
                width = asset?.width ?: peek.width,
                height = asset?.height ?: peek.height,
                bytes = asset?.encodedBytes,
                rgba = asset?.rgba,
                compressedBytes = asset?.compressedBytes,
                texFormat = peek.texFormat,
                imageFormat = peek.imageFormat,
                extension = extension,
                isSceneLayer = layerRefs.isNotEmpty(),
                source = if (deferPayloads) SceneAssetSource(entry.dataOffset, entry.length, mobile) else null
            )
            layerRefs.forEach { layerAssets[it] = sceneAsset }
            maskRefs.forEach { maskAssets[it] = sceneAsset }
            if (!sceneAsset.isSceneLayer && maskRefs.isEmpty()) extraAssets += sceneAsset
        }

        // 图层纹理按 scene.json 数组顺序排列；缺失的图层记入 info.notes 的由调用方展示
        val ordered = info.layers.mapNotNull { layer ->
            layerAssets[layer.textureName]
        }
        val missing = info.layers.mapNotNull { it.textureName }
            .filter { it !in layerAssets.keys }
        val notes = info.notes + missing.map { "图层纹理「$it」解码失败或缺失" }

        return SceneExtractResult(
            info = info.copy(notes = notes),
            assets = (ordered + extraAssets).distinctBy { it.sourceEntry },
            layerAssets = layerAssets,
            maskAssets = maskAssets
        )
    }
}
