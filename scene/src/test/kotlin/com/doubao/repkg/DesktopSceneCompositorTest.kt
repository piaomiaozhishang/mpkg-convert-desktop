package com.doubao.repkg

import com.mpkg.scene.core.*
import com.mpkg.scene.desktop.DesktopSceneCompositor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopSceneCompositorTest {
    private fun asset(name: String, r: Int, g: Int, b: Int, a: Int = 255) = SceneAsset(
        "$name.png", name, "materials/$name.tex", 1, 1, null,
        byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte()), null, 0, -1, "png", true)
    private fun layer(name: String, scale: Float = 1f, visible: Boolean = true) =
        SceneLayer(name, name, 2f, 2f, scale, scale, 0f, visible, null)
    private fun scene(layers: List<SceneLayer>, vararg assets: SceneAsset) =
        SceneExtractResult(SceneInfo(4, 4, layers, 0, emptyList()), assets.toList(), assets.associateBy { it.textureName })

    @Test fun drawsInArrayOrderAndPreservesPlacementAndChannels() {
        val result = DesktopSceneCompositor.render(scene(listOf(layer("blue"), layer("red", .5f)),
            asset("blue", 0, 0, 255), asset("red", 255, 0, 0)))
        assertEquals(0xff0000ff.toInt(), result.getRGB(0, 0))
        assertEquals(0xffff0000.toInt(), result.getRGB(1, 1))
        assertEquals(0xff0000ff.toInt(), result.getRGB(3, 3))
        result.flush()
    }

    @Test fun alphaBlendsSourceOverBackground() {
        val result = DesktopSceneCompositor.render(scene(listOf(layer("blue"), layer("red")),
            asset("blue", 0, 0, 255), asset("red", 255, 0, 0, 128)))
        val pixel = result.getRGB(2, 2)
        assertEquals(255, pixel ushr 24)
        assertTrue(((pixel ushr 16) and 255) in 127..129)
        assertTrue((pixel and 255) in 126..128)
        result.flush()
    }

    @Test fun storedVisibilityCanBeRespectedInsteadOfShowingHiddenLayers() {
        val data = scene(listOf(layer("blue"), layer("red", visible = false)),
            asset("blue", 0, 0, 255), asset("red", 255, 0, 0))
        val image = DesktopSceneCompositor.render(data)
        assertEquals(0xff0000ff.toInt(), image.getRGB(0, 0))
        image.flush()
    }

    @Test fun rotationUsesSameNegativeAngleAsAndroid() {
        val red = layer("red", .25f).copy(originX = 2.5f, originY = 1.5f, scaleX = .5f, angleZDeg = 90f)
        val image = DesktopSceneCompositor.render(scene(listOf(red), asset("red", 255, 0, 0)))
        // A horizontal 2x1 rectangle becomes a vertical 1x2 rectangle around the center.
        assertEquals(0, image.getRGB(1, 2) ushr 24)
        assertTrue((image.getRGB(2, 2) ushr 24) > 0)
        image.flush()
    }

    @Test fun smallLayerUsesOwnSizeAndBottomLeftSceneCoordinates() {
        val small = layer("red").copy(width = 1f, height = 1f, originX = .5f, originY = 3.5f)
        val image = DesktopSceneCompositor.render(scene(listOf(layer("blue"), small),
            asset("blue", 0, 0, 255), asset("red", 255, 0, 0)))
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xff0000ff.toInt(), image.getRGB(0, 3))
        assertEquals(0xff0000ff.toInt(), image.getRGB(1, 0))
        image.flush()
    }

    @Test fun redMaskChannelControlsTransparencyEvenWhenMaskAlphaIsOpaque() {
        val masked = layer("red").copy(opacityMasks = listOf(SceneOpacityMask("masks/a")))
        val mask = asset("masks/a", 0, 255, 255)
        val data = scene(listOf(layer("blue"), masked), asset("blue", 0, 0, 255), asset("red", 255, 0, 0))
            .copy(maskAssets = mapOf("masks/a" to mask))
        val image = DesktopSceneCompositor.render(data)
        assertEquals(0xff0000ff.toInt(), image.getRGB(2, 2))
        image.flush()
        assertThrows(IllegalStateException::class.java) { DesktopSceneCompositor.render(data.copy(maskAssets = emptyMap())) }
    }

    @Test fun opacityAndTintAreAppliedBeforeCompositing() {
        val image = DesktopSceneCompositor.render(scene(listOf(layer("white").copy(opacity = .5f,
            tint = listOf(1f, 0f, 0f))), asset("white", 255, 255, 255)))
        val pixel = image.getRGB(2, 2)
        assertTrue((pixel ushr 24) in 127..128)
        assertEquals(0xff0000, pixel and 0xffffff)
        image.flush()
    }

    @Test fun opaqueEncodedImagesCanAcquireTransparencyFromMask() {
        val rgb = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        rgb.setRGB(0, 0, 0xffff0000.toInt())
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(rgb, "png", it) }.toByteArray()
        rgb.flush()
        val red = asset("red", 255, 0, 0).copy(bytes = png, rgba = null)
        val image = DesktopSceneCompositor.render(scene(listOf(layer("red").copy(
            opacityMasks = listOf(SceneOpacityMask(null, .5f)))), red))
        assertTrue((image.getRGB(2, 2) ushr 24) in 127..128)
        image.flush()
    }

    @Test fun missingLayersAndInsufficientBudgetFailExplicitly() {
        val data = scene(listOf(layer("missing")))
        assertThrows(IllegalStateException::class.java) { DesktopSceneCompositor.render(data) }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSceneCompositor.render(data, DesktopSceneCompositor.Options(maxWorkingBytes = 16))
        }
    }

    @Test fun pngPreservesTransparencyAndNeverOverwritesExistingFile() {
        val image = DesktopSceneCompositor.render(scene(listOf(layer("red", .5f)), asset("red", 255, 0, 0)))
        val directory = Files.createTempDirectory("scene-png-test").toFile()
        val file = File(directory, "result.png")
        try {
            DesktopSceneCompositor.writePng(image, file)
            val loaded = javax.imageio.ImageIO.read(file)
            assertEquals(0, loaded.getRGB(0, 0) ushr 24)
            assertEquals(0xffff0000.toInt(), loaded.getRGB(1, 1))
            loaded.flush()
            assertThrows(IllegalArgumentException::class.java) { DesktopSceneCompositor.writePng(image, file) }
        } finally { image.flush(); file.delete(); directory.delete() }
    }
}
