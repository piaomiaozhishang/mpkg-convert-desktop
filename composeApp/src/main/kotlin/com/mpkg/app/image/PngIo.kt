package com.mpkg.app.image

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 一张已经读进内存的位图（ARGB8888，0xAARRGGBB）。 */
class Raster(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) {
            "像素数量 ${argb.size} 与尺寸 ${width}x$height 不符"
        }
    }

    /** 去掉 alpha 的 RGB 值；逐像素比对只看 RGB。 */
    fun rgbAt(index: Int): Int = argb[index] and 0xFFFFFF

    fun rgbCopy(): IntArray = IntArray(argb.size) { argb[it] and 0xFFFFFF }
}

/**
 * PNG 读写。桌面端映射关系见《技术总结与移植指南》第五节：
 * Android 的 BitmapFactory / Bitmap.compress → 桌面 javax.imageio。
 */
object PngIo {

    /**
     * 把 ARGB 像素写成 PNG。
     *
     * 全部像素不透明时按 TYPE_INT_RGB 写出（PNG 颜色类型 2）——与样本
     * `sample/expected_1280x720.png` 一致，同时体积更小；含透明像素时按 ARGB 写出。
     */
    fun writeArgb(argb: IntArray, width: Int, height: Int, file: File) {
        require(argb.size == width * height) {
            "像素数量 ${argb.size} 与尺寸 ${width}x$height 不符"
        }
        val opaque = argb.all { (it ushr 24) == 0xFF }
        val type = if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val image = BufferedImage(width, height, type)
        image.setRGB(0, 0, width, height, argb, 0, width)

        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw java.io.IOException("无法创建目录: $parent")
        }
        if (!ImageIO.write(image, "png", file)) {
            throw java.io.IOException("没有可用的 PNG 编码器")
        }
    }

    /**
     * 把 RGBA8888 字节（core 解码器的输出顺序）写成 PNG。
     * 顺序原样保留，**不交换 R/B**（红线 #3）。
     */
    fun writeRgba(rgba: ByteArray, width: Int, height: Int, file: File) {
        writeArgb(rgbaToArgb(rgba, width, height), width, height, file)
    }

    /** RGBA8888 字节 → ARGB int 数组，通道顺序不变。 */
    fun rgbaToArgb(rgba: ByteArray, width: Int, height: Int): IntArray {
        require(rgba.size >= width * height * 4) {
            "RGBA 数据长度 ${rgba.size} 小于 ${width}x$height x4"
        }
        val out = IntArray(width * height)
        var s = 0
        for (i in out.indices) {
            val r = rgba[s].toInt() and 0xFF
            val g = rgba[s + 1].toInt() and 0xFF
            val b = rgba[s + 2].toInt() and 0xFF
            val a = rgba[s + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            s += 4
        }
        return out
    }

    /** 读取任意 ImageIO 支持的图片为 [Raster]；无法识别时返回 null。 */
    fun read(file: File): Raster? {
        val image = ImageIO.read(file) ?: return null
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return Raster(image.width, image.height, argb)
    }

    /**
     * 直接从内存中的编码字节读图（用于包内嵌 PNG/JPEG 缩略图，不需要落盘）。
     */
    fun readBytes(bytes: ByteArray): Raster? {
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return Raster(image.width, image.height, argb)
    }
}
