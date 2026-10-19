package com.mpkg.app.export

import com.mpkg.app.extract.DesktopExtractor
import com.mpkg.app.extract.OutputPayload
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class SceneIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun int(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun str(s: String) = int(s.toByteArray().size) + s.toByteArray()
    private fun tex(color: Int): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..3) for (x in 0..3) image.setRGB(x, y, color)
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        image.flush()
        return "TEXV0005\u0000TEXI0001\u0000".toByteArray() +
            listOf(0, 0, 4, 4, 4, 4, 0).flatMap { int(it).toList() }.toByteArray() +
            "TEXB0003\u0000".toByteArray() +
            listOf(1, 13, 1, 4, 4, 0, 0, png.size).flatMap { int(it).toList() }.toByteArray() + png
    }
    private fun fixture(video: ByteArray? = null, single: Boolean = false, shared: Boolean = false,
                        withMask: Boolean = false): File {
        val objects = """{"name":"背景","image":"models/bg.json","size":"4 4","origin":"2 2 0"}""" +
            if (single) "" else """,{"name":"角色","image":"models/fg.json","size":"4 4","origin":"2 2 0","scale":"0.5 0.5 1",
                "effects":${if (withMask) """[{"file":"effects/opacity/effect.json","passes":[{"textures":[null,"masks/hidden"]}]}]""" else "[]"}}"""
        val entries = linkedMapOf(
            "scene.json" to """{"objects":[$objects]}""".toByteArray(),
            "models/bg.json" to """{"autosize":true,"material":"materials/bg.json"}""".toByteArray(),
            "materials/bg.json" to """{"passes":[{"textures":["bg"]}]}""".toByteArray(),
            "materials/bg.tex" to tex(0xff0000ff.toInt())
        )
        if (!single) {
            entries["models/fg.json"] = """{"autosize":true,"material":"materials/fg.json"}""".toByteArray()
            entries["materials/fg.json"] = """{"passes":[{"textures":["${if (shared) "bg" else "fg"}"]}]}""".toByteArray()
            if (!shared) entries["materials/fg.tex"] = tex(0xffff0000.toInt())
        }
        if (withMask) entries["materials/masks/hidden.tex"] = tex(0xff000000.toInt())
        if (video != null) entries["video.mp4"] = video
        var header = str("PKGV0009") + int(entries.size)
        var offset = 0
        entries.forEach { (name, bytes) -> header += str(name) + int(offset) + int(bytes.size); offset += bytes.size }
        return temp.newFile("scene-${System.nanoTime()}.pkg").apply {
            outputStream().use { out -> out.write(header); entries.values.forEach(out::write) }
        }
    }

    @Test fun packageToCompositeUsesExistingDesktopPipelineAndThumbnail() {
        val source = fixture()
        val analysis = DesktopExtractor.analyzePackage(source)
        val main = requireNotNull(analysis.main)
        assertTrue(main.payload is OutputPayload.Scene)
        val output = requireNotNull(Exporter.write(main, temp.newFolder(), OverwritePolicy.RENAME))
        val image = ImageIO.read(output)
        assertEquals(4, image.width)
        assertEquals(0xff0000ff.toInt(), image.getRGB(0, 0))
        assertEquals(0xffff0000.toInt(), image.getRGB(1, 1))
        image.flush()
        assertNotNull(ImageIO.read(SceneExporter.thumbnail(output).inputStream()))
        assertNull(Exporter.write(main, output.parentFile, OverwritePolicy.SKIP))
    }

    @Test fun singleImageAndVideoKeepTheirOriginalExportRoutes() {
        assertFalse(requireNotNull(DesktopExtractor.analyzePackage(fixture(single = true)).main).payload is OutputPayload.Scene)
        val video = byteArrayOf(0,0,0,24) + "ftypisom".toByteArray() + ByteArray(12)
        val analysis = DesktopExtractor.analyzePackage(fixture(video))
        assertTrue(analysis.isVideo)
        val written = requireNotNull(Exporter.write(requireNotNull(analysis.main), temp.newFolder(), OverwritePolicy.RENAME))
        assertArrayEquals(video, written.readBytes())
    }

    @Test fun cancellationDoesNotReplaceExistingOutput() {
        val main = requireNotNull(DesktopExtractor.analyzePackage(fixture()).main)
        val dir = temp.newFolder()
        val existing = File(dir, main.suggestedName).apply { writeText("existing") }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            Exporter.write(main, dir, OverwritePolicy.OVERWRITE,
                checkActive = { throw java.util.concurrent.CancellationException("test") })
        }
        assertEquals("existing", existing.readText())
    }

    @Test fun sharedTextureStillCompositesEveryObject() {
        val main = requireNotNull(DesktopExtractor.analyzePackage(fixture(shared = true)).main)
        val payload = main.payload as OutputPayload.Scene
        assertEquals(2, payload.scene.info.layers.size)
        assertEquals(1, payload.scene.layerAssets.size)
        assertNotNull(Exporter.write(main, temp.newFolder(), OverwritePolicy.RENAME))
    }

    @Test fun opacityMaskLoadsFromNestedTexturePathAndHidesForeground() {
        val main = requireNotNull(DesktopExtractor.analyzePackage(fixture(withMask = true)).main)
        val payload = main.payload as OutputPayload.Scene
        assertEquals(1, payload.scene.maskAssets.size)
        val file = requireNotNull(Exporter.write(main, temp.newFolder(), OverwritePolicy.RENAME))
        val image = ImageIO.read(file)
        assertEquals(0xff0000ff.toInt(), image.getRGB(1, 1))
        image.flush()
    }
}
