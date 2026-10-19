package com.mpkg.scene.core

/** Kind of an extracted asset. */
enum class AssetKind { VIDEO, IMAGE }

/**
 * A payload that can be copied straight out of the package file.
 *
 * Set on large videos: [dataOffset]/[length] address a byte range of the source
 * .mpkg, so exporting never has to hold the MP4 in the heap. The range already
 * points past any `.tex` header, i.e. the bytes are a bare MP4 file.
 */
data class StreamSpec(
    /** Absolute offset inside the source package. */
    val dataOffset: Long,
    /** Number of bytes to copy. */
    val length: Long,
    /** True when the range already is an MP4 file (tex header skipped). */
    val isMp4: Boolean
)

/**
 * Assets at or above this size are exported by streaming the source range
 * instead of buffering it. Below it, keeping `bytes` preserves the card preview
 * and the existing small-file behaviour.
 */
const val STREAM_THRESHOLD_BYTES = 64L * 1024 * 1024

/** One exportable result (a video or a picture). */
data class ExtractedAsset(
    val kind: AssetKind,
    /** Suggested file name, including extension. */
    val fileName: String,
    /** Path of the package entry the asset came from. */
    val sourceEntry: String,
    /** Ready-to-save file bytes (mp4 / encoded image). Null when pixels/compressed or [streamSource] are used. */
    val bytes: ByteArray? = null,
    /** Decoded RGBA8888 pixels needing Bitmap encoding (raw/DXT textures). */
    val pixelsRgba: ByteArray? = null,
    /** Compressed block stream (ETC2) needing platform GPU decoding. */
    val compressedBytes: ByteArray? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    /** True for the wallpaper's main full-resolution content. */
    val isPrimary: Boolean = false,
    /**
     * When set, the payload lives in the source file and must be streamed out
     * (see [STREAM_THRESHOLD_BYTES]); [bytes] is null in that case.
     */
    val streamSource: StreamSpec? = null,
    val note: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedAsset) return false
        return kind == other.kind && fileName == other.fileName && sourceEntry == other.sourceEntry
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + sourceEntry.hashCode()
        return result
    }
}

/**
 * The analyzed contents of one wallpaper package: a single main piece
 * (original picture OR video) plus, when available, a preview picture.
 * Everything else (UI icons, masks, widgets) is intentionally dropped.
 */
class WallpaperResult(
    val isVideo: Boolean,
    /** Main content: original image (static) or video (dynamic). May be null if undecodable. */
    val main: ExtractedAsset?,
    /** Preview picture, when the package provides one. */
    val preview: ExtractedAsset?
)

/** Progress callback while scanning a package. */
fun interface ExtractProgress {
    fun onProgress(current: Int, total: Int, message: String)
}

/** Image extensions saved verbatim when found as plain package entries. */
private val PLAIN_IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tga", ".dds")

/**
 * High level facade: analyze an mpkg/pkg and return only the wallpaper's main
 * content and its preview. The caller decides which piece the user exports.
 */
object Extractor {

    fun analyze(
        src: SeekableData,
        lz4: Lz4BlockDecompressor? = null,
        progress: ExtractProgress? = null,
        maxWorkingBytes: Long = Long.MAX_VALUE,
        streamAllVideos: Boolean = false
    ): WallpaperResult {
        val archive = PkgReader.read(src)
        val isMobile = archive.magic.startsWith("PKGM")
        val total = archive.entries.size

        // Pass 1: classify entries (tex headers are peeked, no pixel payload).
        val videoEntries = ArrayList<PkgEntry>()
        val imageTexCandidates = ArrayList<ImageRanker.Candidate>()
        val plainImageEntries = ArrayList<PkgEntry>()

        archive.entries.forEachIndexed { index, entry ->
            progress?.onProgress(index, total, entry.path)
            when {
                entry.extension == ".mp4" -> videoEntries.add(entry)
                entry.extension == ".tex" -> {
                    val peek = peekTexHeader(src, entry, isMobile)
                    when {
                        peek == null -> { /* unreadable */ }
                        peek.isVideo -> videoEntries.add(entry)
                        else -> imageTexCandidates.add(
                            ImageRanker.Candidate(
                                path = entry.path,
                                width = peek.width,
                                height = peek.height,
                                texFormat = peek.texFormat,
                                imageFormat = peek.imageFormat
                            )
                        )
                    }
                }
                entry.extension in PLAIN_IMAGE_EXTENSIONS -> plainImageEntries.add(entry)
                else -> { /* json, audio, scripts: ignored */ }
            }
        }

        val usedNames = HashSet<String>()
        val isVideo = videoEntries.isNotEmpty()

        fun entryFor(path: String) = archive.entries.first { it.path == path }

        // --- Main content ---
        var main: ExtractedAsset? = null
        if (isVideo) {
            // Resolve every candidate to its byte range first (headers only), then
            // try them longest-first. Nothing reads a video payload to *compare*
            // entries: loading a 3 GB MP4 just to measure it is what used to OOM.
            // The loop keeps the old "skip an unusable video" behaviour by falling
            // through to the next candidate.
            val candidates = videoEntries
                .mapNotNull { ve -> videoSpan(src, ve, isMobile)?.let { ve to it } }
                .sortedByDescending { it.first.length }

            for ((entry, span) in candidates) {
                val asset = buildVideoAsset(src, entry, span, isMobile, lz4, usedNames, streamAllVideos, maxWorkingBytes)
                if (asset != null) {
                    main = asset
                    break
                }
            }
        } else {
            // Best (largest, art-like) picture texture, best first.
            val ranked = ImageRanker.rank(imageTexCandidates)
            for (rankedItem in ranked) {
                val entry = entryFor(rankedItem.candidate.path)
                val asset = try {
                    TexAssetDecoder.decodeSource(
                        SliceSeekableData(src, entry.dataOffset, entry.length), lz4, isMobile, maxWorkingBytes
                    )
                } catch (e: RuntimeException) {
                    null
                }
                if (asset is TexAsset.Image) {
                    main = toImageExtracted(usedNames, entry, asset, primary = true,
                        note = "主图（完整分辨率）")
                    break
                }
            }
        }

        if (!isVideo && main == null) {
            val image = plainImageEntries.filterNot { it.path.contains("preview", ignoreCase = true) }
                .maxByOrNull { it.length }
            if (image != null) {
                main = ExtractedAsset(
                    AssetKind.IMAGE, uniqueName(usedNames, image.nameWithoutExtension, image.extension.removePrefix(".")),
                    image.path, isPrimary = true,
                    streamSource = StreamSpec(image.dataOffset, image.length, isMp4 = false)
                )
            }
        }

        val mainSource = main?.sourceEntry

        // --- Preview picture: prefer a plain preview.*, then an encoded tex ---
        val isPreviewPath: (String) -> Boolean = { p ->
            p != mainSource && p.contains("preview", ignoreCase = true)
        }

        var preview: ExtractedAsset? = null

        // 1) Plain preview image (e.g. preview.jpg) verbatim.
        val plain = plainImageEntries.firstOrNull { isPreviewPath(it.path) }
        if (plain != null && plain.length <= maxWorkingBytes) {
            val b = PkgReader.readEntryBytes(src, plain)
            preview = ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(usedNames, plain.nameWithoutExtension, plain.extension.removePrefix(".")),
                plain.path,
                bytes = b,
                note = "预览图"
            )
        }

        // 2) Preview tex carrying an already-encoded payload (JPEG/PNG).
        if (preview == null) {
            for (c in imageTexCandidates) {
                if (!isPreviewPath(c.path)) continue
                val entry = entryFor(c.path)
                val asset = decodeImage(src, entry, isMobile, lz4, maxWorkingBytes) ?: continue
                if (asset.encodedBytes != null) {
                    preview = ExtractedAsset(
                        AssetKind.IMAGE,
                        uniqueName(usedNames, entry.nameWithoutExtension, asset.extension),
                        entry.path,
                        bytes = asset.encodedBytes,
                        note = "预览图"
                    )
                    break
                }
            }
        }

        // 3) Preview tex with raw pixels -> encode on the platform side.
        if (preview == null) {
            for (c in imageTexCandidates) {
                if (!isPreviewPath(c.path)) continue
                val entry = entryFor(c.path)
                val asset = decodeImage(src, entry, isMobile, lz4, maxWorkingBytes) ?: continue
                if (asset.rgba != null) {
                    preview = ExtractedAsset(
                        AssetKind.IMAGE,
                        uniqueName(usedNames, entry.nameWithoutExtension, "png"),
                        entry.path,
                        pixelsRgba = asset.rgba,
                        pixelWidth = asset.width,
                        pixelHeight = asset.height,
                        note = "预览图"
                    )
                    break
                }
            }
        }

        progress?.onProgress(total, total, "完成")
        return WallpaperResult(isVideo, main, preview)
    }

    // --- Decoding helpers ---

    /**
     * Byte range of one video candidate inside the package.
     *
     * @param dataOffset absolute offset of the payload (past any .tex header)
     * @param length     payload length; for LZ4 mipmaps this is the *stored* size
     * @param copyable   true when the range is a bare MP4 that can be copied out
     *                   verbatim; false for LZ4 mipmaps, which must be inflated
     */
    private class VideoSpan(
        val dataOffset: Long,
        val length: Long,
        val copyable: Boolean
    )

    /**
     * Resolves a video candidate to its payload range **without reading it**.
     * Returns null when the entry is not a usable video.
     */
    private fun videoSpan(src: SeekableData, entry: PkgEntry, isMobile: Boolean): VideoSpan? {
        if (entry.length <= 0) return null

        // A plain .mp4 entry is already the finished file.
        if (entry.extension == ".mp4") {
            return VideoSpan(entry.dataOffset, entry.length, copyable = true)
        }
        if (entry.extension != ".tex") return null

        val span = try {
            TexAssetDecoder.locateFirstMipmap(
                SliceSeekableData(src, entry.dataOffset, entry.length), isMobile
            )
        } catch (e: Exception) {
            null
        } ?: return null
        if (!span.isVideo) return null

        val length = span.byteCount.toLong()
        if (length <= 0 || span.payloadOffset + length > entry.length) return null
        val payloadAt = entry.dataOffset + span.payloadOffset

        // LZ4 mipmaps must be inflated, and inflation needs the stored block in
        // memory, so they keep using the buffered decode path.
        if (span.isLz4) return VideoSpan(payloadAt, length, copyable = false)

        // 12-byte brand probe: keeps the old "skip an unplayable video texture"
        // behaviour, at the cost of twelve bytes instead of the whole payload.
        if (!TexAssetDecoder.looksLikeMp4(src.readAt(payloadAt, minOf(12L, length).toInt()))) {
            return null
        }
        return VideoSpan(payloadAt, length, copyable = true)
    }

    /**
     * Materialises the chosen video as an [ExtractedAsset].
     *
     * Large payloads become a [StreamSpec] (export copies the range), small ones
     * keep `bytes` so the preview card and history thumbnails stay unchanged.
     */
    private fun buildVideoAsset(
        src: SeekableData,
        entry: PkgEntry,
        span: VideoSpan,
        isMobile: Boolean,
        lz4: Lz4BlockDecompressor?,
        used: MutableSet<String>,
        streamAllVideos: Boolean,
        maxWorkingBytes: Long
    ): ExtractedAsset? {
        val streamed = span.copyable && (streamAllVideos || span.length >= STREAM_THRESHOLD_BYTES)

        val bytes: ByteArray?
        val streamSource: StreamSpec?
        if (streamed) {
            bytes = null
            streamSource = StreamSpec(span.dataOffset, span.length, isMp4 = true)
        } else {
            bytes = if (span.copyable) {
                src.readAt(span.dataOffset, span.length.toInt())
            } else {
                decodeVideo(src, entry, isMobile, lz4, maxWorkingBytes) ?: return null
            }
            streamSource = null
        }

        return ExtractedAsset(
            AssetKind.VIDEO,
            uniqueName(used, entry.nameWithoutExtension, "mp4"),
            entry.path,
            bytes = bytes,
            streamSource = streamSource,
            isPrimary = true,
            note = "视频"
        )
    }

    /**
     * Peek a .tex header straight off the package.
     *
     * The reader only touches the fixed header fields (plus a v4 condition
     * string), so this is safe for a multi-GB video texture. The previous
     * 4 KB-prefix-then-full-entry fallback would load the entire entry whenever
     * the header did not fit the window.
     */
    private fun peekTexHeader(src: SeekableData, entry: PkgEntry, isMobile: Boolean): TexPeek? {
        // NOTE: a short/truncated entry throws EOFException (an IOException, NOT a
        // RuntimeException), so catch Exception here, not RuntimeException.
        return try {
            TexAssetDecoder.peekSource(
                SliceSeekableData(src, entry.dataOffset, entry.length), isMobile
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeVideo(
        src: SeekableData, entry: PkgEntry, isMobile: Boolean, lz4: Lz4BlockDecompressor?, maxWorkingBytes: Long
    ): ByteArray? {
        if (entry.extension == ".mp4") return PkgReader.readEntryBytes(src, entry)
        val asset = try {
            TexAssetDecoder.decodeSource(SliceSeekableData(src, entry.dataOffset, entry.length), lz4, isMobile, maxWorkingBytes)
        } catch (e: RuntimeException) {
            null
        }
        return (asset as? TexAsset.Video)?.bytes
    }

    private fun decodeImage(
        src: SeekableData, entry: PkgEntry, isMobile: Boolean, lz4: Lz4BlockDecompressor?, maxWorkingBytes: Long
    ): TexAsset.Image? {
        if (entry.extension != ".tex") return null
        val asset = try {
            TexAssetDecoder.decodeSource(SliceSeekableData(src, entry.dataOffset, entry.length), lz4, isMobile, maxWorkingBytes)
        } catch (e: RuntimeException) {
            null
        }
        return asset as? TexAsset.Image
    }

    private fun toImageExtracted(
        used: MutableSet<String>,
        entry: PkgEntry,
        asset: TexAsset.Image,
        primary: Boolean,
        note: String
    ): ExtractedAsset {
        return when {
            asset.encodedBytes != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, asset.extension),
                entry.path,
                bytes = asset.encodedBytes,
                isPrimary = primary,
                note = note
            )
            asset.rgba != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, "png"),
                entry.path,
                pixelsRgba = asset.rgba,
                pixelWidth = asset.width,
                pixelHeight = asset.height,
                isPrimary = primary,
                note = if (primary) "主图（解码自纹理，PNG 无损）" else "图片"
            )
            asset.compressedBytes != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, "png"),
                entry.path,
                compressedBytes = asset.compressedBytes,
                pixelWidth = asset.width,
                pixelHeight = asset.height,
                isPrimary = primary,
                note = if (primary) "主图（ETC2 GPU 解码，PNG 无损）" else "ETC2 纹理"
            )
            else -> error("Image asset has no payload")
        }
    }

    private fun uniqueName(used: MutableSet<String>, base: String, extension: String): String {
        val safeBase = base.ifBlank { "wallpaper" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var name = "$safeBase.$extension"
        var i = 1
        while (!used.add(name)) {
            name = "${safeBase}_$i.$extension"
            i++
        }
        return name
    }
}
