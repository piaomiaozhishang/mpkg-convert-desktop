package com.doubao.repkg

import com.mpkg.scene.core.ByteArraySeekableData
import com.mpkg.scene.core.SceneExtractor
import com.mpkg.scene.core.WallpaperAnalyzer
import com.mpkg.scene.core.WallpaperAnalysis
import com.mpkg.scene.core.SeekableData
import com.mpkg.scene.core.TexAssetDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SceneExtractor 的单测：合成 fake 场景包 -> 全量提取。
 * 艺术纹理用 imageFormat=13（PNG）内嵌字节，decode 后原样带回。
 */
class SceneExtractorTest {

    private fun le32(n: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    private fun i32s(s: ByteArray): ByteArray = le32(s.size) + s

    private fun assemble(magic: String, entries: List<Pair<String, ByteArray>>): ByteArray {
        var header = i32s(magic.toByteArray())
        header += le32(entries.size)
        var data = ByteArray(0)
        var cursor = 0
        for ((p, b) in entries) {
            header += i32s(p.toByteArray())
            header += le32(cursor)
            header += le32(b.size)
            data += b
            cursor += b.size
        }
        return header + data
    }

    /** imageFormat=13 的内嵌 PNG 纹理：mip0 字节就是完整图片文件。 */
    private fun embeddedPngTex(payload: ByteArray, w: Int, h: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0)              // texFormat RGBA8888（仅头部位）
        out += le32(0)              // flags
        out += le32(w); out += le32(h)
        out += le32(w); out += le32(h)
        out += le32(0)
        out += "TEXB0003\u0000".toByteArray()
        out += le32(1)              // imageCount
        out += le32(13)             // imageFormat = PNG
        out += le32(1)              // mipmapCount
        out += le32(w); out += le32(h)
        out += le32(0); out += le32(0); out += le32(payload.size)
        out += payload
        return out
    }

    private fun sceneJson(objects: String) = """
        {"objects": [$objects], "version": 0}
    """.trimIndent().toByteArray()

    private fun model(material: String) = """{"autosize": true, "material": "$material"}""".toByteArray()

    private fun material(texture: String) =
        """{"passes": [{"blending": "translucent", "textures": ["$texture"]}]}""".toByteArray()

    private fun scenePackage(): ByteArray = assemble(
        "PKGV0009",
        listOf(
            "scene.json" to sceneJson(
                """
                {"name": "bg", "image": "models/bg.json", "size": "1920.000 1080.000",
                 "origin": "960.000 540.000 0.000"},
                {"name": "hero", "image": "models/hero.json", "size": "1920.000 1080.000",
                 "origin": "971.94098 540.00000 0.000", "scale": "1.500 1.500 1.500"},
                {"name": "hidden", "image": "models/hidden.json", "size": "1920.000 1080.000",
                 "visible": {"user": "bw", "value": false}}
                """
            ),
            "models/bg.json" to model("materials/bg.json"),
            "models/hero.json" to model("materials/hero.json"),
            "models/hidden.json" to model("materials/hidden.json"),
            "materials/bg.json" to material("bg"),
            "materials/hero.json" to material("hero"),
            "materials/hidden.json" to material("hidden"),
            "materials/bg.tex" to embeddedPngTex("PNG-BG".toByteArray(), 1920, 1080),
            "materials/hero.tex" to embeddedPngTex("PNG-HERO".toByteArray(), 1920, 1080),
            "materials/hidden.tex" to embeddedPngTex("PNG-HIDDEN".toByteArray(), 1920, 1080),
            // 遮罩与特效资产不应进结果
            "materials/masks/waterwaves_mask_x.tex" to embeddedPngTex("MASK".toByteArray(), 960, 540),
            "materials/effects/waterripplenormal.tex" to embeddedPngTex("NORMAL".toByteArray(), 256, 256)
        )
    )

    @Test
    fun extractsAllLayersInSceneOrder() {
        val r = SceneExtractor.analyze(ByteArraySeekableData(scenePackage()), lz4 = null)
        assertNotNull(r)
        r!!
        assertEquals(1920, r.info.canvasWidth)
        assertEquals(1080, r.info.canvasHeight)
        assertEquals(3, r.info.layers.size)
        // bg 与 hero 默认可见，hidden 被用户属性关掉 -> 2 层可见
        assertEquals(2, r.info.visibleLayerCount)
        assertEquals(1, r.info.hiddenLayerCount)
        assertEquals("bw", r.info.layers[2].userToggle)

        // 资产：三个图层纹理按数组序在前，遮罩/特效被排除
        assertEquals(3, r.assets.size)
        assertEquals("bg.png", r.assets[0].fileName)
        assertEquals("hero.png", r.assets[1].fileName)
        assertEquals("hidden.png", r.assets[2].fileName)
        assertTrue(r.assets.all { it.isSceneLayer })
        assertEquals("PNG-BG", String(r.assets[0].bytes!!))
        assertEquals(1920, r.assets[0].width)
        assertEquals(1080, r.assets[0].height)

        assertEquals(setOf("bg", "hero", "hidden"), r.layerAssets.keys)
    }

    @Test
    fun layerScaleIsCarriedForCompositing() {
        val r = SceneExtractor.analyze(ByteArraySeekableData(scenePackage()), lz4 = null)!!
        val hero = r.info.layers[1]
        assertEquals(1.5f, hero.scaleX, 1e-4f)
        assertEquals(1.5f, hero.scaleY, 1e-4f)
        assertEquals(971.94098f, hero.originX, 1e-3f)
        assertTrue(hero.canComposite)
    }

    @Test
    fun packageWithoutSceneJsonReturnsNull() {
        val pkg = assemble(
            "PKGM0019",
            listOf(
                "materials/wallpaper.tex" to embeddedPngTex("PNG".toByteArray(), 2, 2)
            )
        )
        assertNull(SceneExtractor.analyze(ByteArraySeekableData(pkg), lz4 = null))
    }

    @Test
    fun extraArtTextureOutsideLayersIsKeptAsExtra() {
        val pkg = assemble(
            "PKGV0009",
            listOf(
                "scene.json" to sceneJson(
                    """{"name": "bg", "image": "models/bg.json", "size": "1920.000 1080.000"}"""
                ),
                "models/bg.json" to model("materials/bg.json"),
                "materials/bg.json" to material("bg"),
                "materials/bg.tex" to embeddedPngTex("PNG-BG".toByteArray(), 1920, 1080),
                // 备胎角色：没有图层引用，但属于艺术纹理，应导出
                "materials/spare.tex" to embeddedPngTex("PNG-SPARE".toByteArray(), 1920, 1080)
            )
        )
        val r = SceneExtractor.analyze(ByteArraySeekableData(pkg), lz4 = null)!!
        assertEquals(2, r.assets.size)
        assertTrue(r.assets[0].isSceneLayer)
        assertFalse(r.assets[1].isSceneLayer)
        assertEquals("spare.png", r.assets[1].fileName)
        assertTrue(r.info.notes.isEmpty())
    }
    @Test
    fun sceneWithVideoBypassesCompositingAndKeepsVideoStreamed() {
        val video = le32(24) + "ftypisom".toByteArray() + ByteArray(128_000)
        val pkg = assemble("PKGV0009", listOf(
            "scene.json" to sceneJson(""),
            "videos/main.mp4" to video
        ))
        val result = WallpaperAnalyzer.analyze(ByteArraySeekableData(pkg)) as WallpaperAnalysis.Classic
        assertTrue(result.wallpaper.isVideo)
        assertNotNull(result.wallpaper.main?.streamSource)
        assertNull(result.wallpaper.main?.bytes)
        assertEquals(video.size.toLong(), result.wallpaper.main!!.streamSource!!.length)
    }

    @Test
    fun sceneWithEmbeddedVideoAlsoBypassesCompositing() {
        val video = le32(24) + "ftypisom".toByteArray() + ByteArray(1024)
        val tex = embeddedPngTex(video, 1920, 1080)
        // Two NUL-terminated 9-byte magic strings precede format and flags.
        le32(32).copyInto(tex, 22)
        val pkg = assemble("PKGV0009", listOf("scene.json" to sceneJson(""), "materials/video.tex" to tex))
        val result = WallpaperAnalyzer.analyze(ByteArraySeekableData(pkg)) as WallpaperAnalysis.Classic
        assertTrue(result.wallpaper.isVideo)
        assertNotNull(result.wallpaper.main?.streamSource)
        assertNull(result.wallpaper.main?.bytes)
    }

    @Test
    fun singleImageSceneUsesDirectImageExport() {
        val payload = "SINGLE-PNG".toByteArray()
        val pkg = assemble("PKGV0009", listOf(
            "scene.json" to sceneJson("""{"name":"bg","image":"models/bg.json","size":"1920 1080"}"""),
            "models/bg.json" to model("materials/bg.json"),
            "materials/bg.json" to material("bg"),
            "materials/bg.tex" to embeddedPngTex(payload, 1920, 1080)
        ))
        val result = WallpaperAnalyzer.analyze(ByteArraySeekableData(pkg)) as WallpaperAnalysis.Classic
        assertFalse(result.wallpaper.isVideo)
        org.junit.Assert.assertArrayEquals(payload, result.wallpaper.main!!.bytes)
    }

    @Test
    fun singlePlainImageIsExportableEvenWithSceneMetadata() {
        val pkg = assemble("PKGV0009", listOf(
            "scene.json" to sceneJson(""), "wallpaper.png" to ByteArray(300)
        ))
        val result = WallpaperAnalyzer.analyze(ByteArraySeekableData(pkg)) as WallpaperAnalysis.Classic
        assertFalse(result.wallpaper.isVideo)
        assertEquals("wallpaper.png", result.wallpaper.main!!.fileName)
        assertEquals(300L, result.wallpaper.main!!.streamSource!!.length)
    }

    @Test
    fun compositeRouteKeepsOnlyDescriptorsUntilEachLayerIsRequested() {
        val data = ByteArraySeekableData(scenePackage())
        val result = WallpaperAnalyzer.analyze(data) as WallpaperAnalysis.Scene
        assertEquals(3, result.scene.layerAssets.size)
        result.scene.assets.forEach {
            assertNull(it.bytes)
            assertNull(it.rgba)
            assertNull(it.compressedBytes)
            assertNotNull(it.source)
        }
        val first = result.scene.assets.first()
        val loaded = SceneExtractor.loadAsset(data, first, null)
        assertEquals("PNG-BG", String(loaded.bytes!!))
        assertNull(loaded.source)
        // Loading one layer never mutates/retains the payload in the descriptor list.
        assertNull(first.bytes)
        assertNull(result.scene.assets[1].bytes)
    }

    @Test
    fun deferredScanNeverReadsLargeTexturePayloads() {
        val payload = ByteArray(1024 * 1024) { 7 }
        val pkg = assemble("PKGV0009", listOf(
            "scene.json" to sceneJson("""{"name":"bg","image":"models/bg.json","size":"1920 1080"}"""),
            "models/bg.json" to model("materials/bg.json"),
            "materials/bg.json" to material("bg"),
            "materials/bg.tex" to embeddedPngTex(payload, 1920, 1080)
        ))
        val data = ByteArraySeekableData(pkg)
        var totalRead = 0L
        val counted = object : SeekableData {
            override val size = data.size
            override fun readAt(offset: Long, length: Int): ByteArray {
                totalRead += length
                return data.readAt(offset, length)
            }
        }
        val result = SceneExtractor.analyze(counted, null, deferPayloads = true)!!
        assertTrue("metadata scan read $totalRead bytes", totalRead < 4096)
        assertNull(result.assets.single().bytes)
    }

    @Test
    fun decodeBudgetRejectsPayloadBeforeAllocatingIt() {
        val data = ByteArraySeekableData(embeddedPngTex(ByteArray(64_000), 1920, 1080))
        var largestRead = 0
        val counted = object : SeekableData {
            override val size = data.size
            override fun readAt(offset: Long, length: Int): ByteArray {
                largestRead = maxOf(largestRead, length)
                return data.readAt(offset, length)
            }
        }
        try {
            TexAssetDecoder.decodeSource(counted, maxWorkingBytes = 1024)
            org.junit.Assert.fail("Expected memory budget rejection")
        } catch (_: IllegalArgumentException) {
            assertTrue("Payload must not be read", largestRead < 1024)
        }
    }

}
