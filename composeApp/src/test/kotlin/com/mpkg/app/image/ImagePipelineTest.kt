package com.mpkg.app.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color

/** Lanczos 重采样与 PNG 读写（任务卡 T1.3 的比对能力基础）。 */
class ImagePipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `常数图缩放后仍是同一常数`() {
        val w = 90
        val h = 60
        val color = 0xFF3366CC.toInt()
        val src = IntArray(w * h) { color }
        val out = LanczosResampler.resample(src, w, h, w / 3, h / 3)
        assertEquals((w / 3) * (h / 3), out.size)
        out.forEach { assertEquals("常数图应保持不变", 0x3366CC, it and 0xFFFFFF) }
    }

    @Test
    fun `尺寸相同时原样返回`() {
        val src = IntArray(16) { 0xFF000000.toInt() or it }
        assertArrayEquals(src, LanczosResampler.resample(src, 4, 4, 4, 4))
    }

    @Test
    fun `放大也走同一套系数不会崩`() {
        val src = IntArray(4 * 4) { 0xFF102030.toInt() }
        val out = LanczosResampler.resample(src, 4, 4, 12, 12)
        assertEquals(144, out.size)
    }

    @Test
    fun `RGBA 转 ARGB 不交换红蓝通道`() {
        // 纯红像素：R=255,G=0,B=0,A=255
        assertEquals(0xFFFF0000.toInt(), PngIo.rgbaToArgb(byteArrayOf(-1, 0, 0, -1), 1, 1)[0])
        // 纯蓝像素
        assertEquals(0xFF0000FF.toInt(), PngIo.rgbaToArgb(byteArrayOf(0, 0, -1, -1), 1, 1)[0])
    }

    @Test
    fun `PNG 写出后读回像素一致`() {
        val w = 8
        val h = 6
        val argb = IntArray(w * h) { index -> 0xFF000000.toInt() or (index * 7 shl 8) or index }
        val file = temp.newFile("roundtrip.png")
        PngIo.writeArgb(argb, w, h, file)

        val raster = PngIo.read(file)
        assertEquals(w, raster?.width)
        assertEquals(h, raster?.height)
        assertArrayEquals(argb, raster?.argb)
    }

    @Test
    fun `不透明图写成 RGB 类型_含透明像素写成 RGBA 类型`() {
        // 断言 PNG 头里的颜色类型（2=RGB / 6=RGBA），而不是读回来 BufferedImage 的
        // 类型——ImageIO 读 RGB PNG 得到的是 TYPE_3BYTE_BGR，与写入类型无关。
        val opaque = IntArray(4) { 0xFF102030.toInt() }
        val opaqueFile = temp.newFile("opaque.png")
        PngIo.writeArgb(opaque, 2, 2, opaqueFile)
        assertEquals("RGB PNG 颜色类型应为 2", 2, pngColorType(opaqueFile))

        val translucent = IntArray(4) { 0x80102030.toInt() }
        val translucentFile = temp.newFile("translucent.png")
        PngIo.writeArgb(translucent, 2, 2, translucentFile)
        assertEquals("RGBA PNG 颜色类型应为 6", 6, pngColorType(translucentFile))

        val decoded = javax.imageio.ImageIO.read(translucentFile)
        assertEquals(0x80, (decoded.getRGB(0, 0) ushr 24) and 0xFF)
    }

    /** 读 PNG 的 IHDR 颜色类型字节。 */
    private fun pngColorType(file: java.io.File): Int {
        val header = file.readBytes()
        require(header.size > 25 && header[12] == 'I'.code.toByte())
        return header[25].toInt() and 0xFF
    }

    @Test
    fun `PngCompare 对同一张图判定为 PASS`() {
        val w = 20
        val h = 10
        val argb = IntArray(w * h) { 0xFF000000.toInt() or (it * 3) }
        val a = temp.newFile("a.png")
        val b = temp.newFile("b.png")
        PngIo.writeArgb(argb, w, h, a)
        PngIo.writeArgb(argb, w, h, b)
        val result = com.mpkg.app.tools.PngCompare.compare(a, b)
        assertEquals(0L, result.diffPixels)
        assertTrue(result.pass)
    }

    @Test
    fun `PngCompare 在整数倍尺寸时自动缩放对齐`() {
        val w = 30
        val h = 15
        val big = IntArray(w * h) { 0xFF204060.toInt() }
        val small = IntArray((w / 3) * (h / 3)) { 0xFF204060.toInt() }
        val bigFile = temp.newFile("big.png")
        val smallFile = temp.newFile("small.png")
        PngIo.writeArgb(big, w, h, bigFile)
        PngIo.writeArgb(small, w / 3, h / 3, smallFile)
        val result = com.mpkg.app.tools.PngCompare.compare(bigFile, smallFile)
        assertTrue(result.resampled)
        assertEquals(0L, result.diffPixels)
    }

    @Test
    fun `PngCompare 能报出真实差异`() {
        val w = 4
        val h = 4
        val a = temp.newFile("d1.png")
        val b = temp.newFile("d2.png")
        PngIo.writeArgb(IntArray(w * h) { Color.WHITE.rgb }, w, h, a)
        val changed = IntArray(w * h) { Color.WHITE.rgb }.also { it[0] = Color.BLACK.rgb }
        PngIo.writeArgb(changed, w, h, b)
        val result = com.mpkg.app.tools.PngCompare.compare(a, b)
        assertEquals(1L, result.diffPixels)
        assertTrue(result.maxChannelDelta > 0)
        assertTrue(!result.pass)
    }
}
