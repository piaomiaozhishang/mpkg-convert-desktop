package com.mpkg.scene.core

sealed interface WallpaperAnalysis {
    data class Scene(val scene: SceneExtractResult) : WallpaperAnalysis
    data class Classic(val wallpaper: WallpaperResult) : WallpaperAnalysis
}

/** Route by actual media, not merely the presence of scene.json. */
object WallpaperAnalyzer {
    fun analyze(
        src: SeekableData,
        lz4: Lz4BlockDecompressor? = null,
        maxWorkingBytes: Long = Long.MAX_VALUE,
        progress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): WallpaperAnalysis {
        val archive = PkgReader.read(src)
        val mobile = archive.magic.startsWith("PKGM")
        val hasVideo = archive.entries.any { entry ->
            progress(0, 0, "正在识别壁纸类型…")
            entry.extension == ".mp4" || (entry.extension == ".tex" && try {
                TexAssetDecoder.peekSource(
                    SliceSeekableData(src, entry.dataOffset, entry.length), mobile
                )?.isVideo == true
            } catch (_: Exception) { false })
        }
        if (!hasVideo) {
            val scene = SceneExtractor.analyze(src, lz4, SceneProgress(progress), deferPayloads = true)
            // Spare textures and previews do not turn a single-layer scene into a collage.
            if (scene != null && scene.layerAssets.size >= 2) return WallpaperAnalysis.Scene(scene)
        }
        return WallpaperAnalysis.Classic(
            Extractor.analyze(src, lz4, ExtractProgress(progress), maxWorkingBytes, streamAllVideos = true)
        )
    }
}
