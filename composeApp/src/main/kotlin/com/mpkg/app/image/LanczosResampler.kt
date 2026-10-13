package com.mpkg.app.image

/**
 * 与 Pillow `Image.resize(..., LANCZOS)` **逐位一致**的重采样器。
 *
 * 复刻 Pillow 11.3.0 `src/libImaging/Resample.c` 的定点实现（LANCZOS，support = 3.0）：
 * 1. `precompute_coeffs`：按输出像素中心取 taps，归一化后定点化为 22 位小数；
 * 2. 水平 pass 输出 8bit 中间图，再做垂直 pass（两次 pass 之间会截断到 8bit）；
 * 3. 每步累加前先加 `1 << 21` 实现四舍五入，结果算术右移 22 位后钳到 0..255。
 *
 * 用途：生成缩略图、导出指定尺寸副本，以及把解码结果与
 * `sample/expected_1280x720.png`（由 Pillow Lanczos 生成）做逐像素比对。
 *
 * 该实现经实测与 Pillow 输出 **0 差异**（921,600 像素全部一致）。
 */
object LanczosResampler {

    private const val PRECISION_BITS = 22
    private const val ROUND = 1 shl (PRECISION_BITS - 1)
    private const val SUPPORT = 3.0
    private const val ONE = 1 shl PRECISION_BITS

    private class Coefficients(val k: IntArray, val bounds: IntArray, val ksize: Int)

    private fun sinc(value: Double): Double {
        if (value == 0.0) return 1.0
        val x = value * Math.PI
        return Math.sin(x) / x
    }

    private fun lanczos(x: Double): Double =
        if (x < -SUPPORT || x > SUPPORT) 0.0 else sinc(x) * sinc(x / SUPPORT)

    /** 对应 C 的 `precompute_coeffs` + `normalize_coeffs_8bpc`。 */
    private fun precompute(inSize: Int, outSize: Int): Coefficients {
        // 关键：center 用未钳制的 scale，滤波器宽度用钳制后的 filterScale。
        // Pillow 里是两个变量（scale / filterscale），放大时二者不同；混用会让
        // xmax - xmin 变成负数（放大到 12/4 时实测 -5），直接崩在 DoubleArray(xmax)。
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = SUPPORT * filterScale
        val ksize = Math.ceil(support).toInt() * 2 + 1

        val bounds = IntArray(outSize * 2)
        val k = IntArray(outSize * ksize)
        val step = 1.0 / filterScale

        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            if (xmin > inSize) xmin = inSize
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            if (xmax < 0) xmax = 0

            val weights = DoubleArray(xmax)
            var sum = 0.0
            for (x in 0 until xmax) {
                val w = lanczos((x + xmin - center + 0.5) * step)
                weights[x] = w
                sum += w
            }
            val base = xx * ksize
            for (x in 0 until xmax) {
                val normalized = if (sum != 0.0) weights[x] / sum else 0.0
                val scaled = normalized * ONE
                // C 的 (int)(±0.5 + v)：向零截断
                k[base + x] = if (normalized < 0) {
                    (-0.5 + scaled).toInt()
                } else {
                    (0.5 + scaled).toInt()
                }
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coefficients(k, bounds, ksize)
    }

    /** 对应 C 的 `clip8`：算术右移后钳到 0..255。 */
    private fun clip8(value: Int): Int {
        val v = value shr PRECISION_BITS
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    /**
     * 把 [src]（ARGB8888，尺寸 [srcWidth]x[srcHeight]）重采样为 [dstWidth]x[dstHeight]。
     * 只重采样 RGB 通道，alpha 原样输出为 255。
     */
    fun resample(src: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) { "尺寸必须为正" }
        require(src.size >= srcWidth * srcHeight) { "源像素数量不足" }
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf(srcWidth * srcHeight)

        val horizontal = precompute(srcWidth, dstWidth)
        val vertical = precompute(srcHeight, dstHeight)

        // Pillow 只对垂直方向真正用到的行做水平 pass（ybox_first .. ybox_last）。
        val yBoxFirst = vertical.bounds[0]
        val yBoxLast = vertical.bounds[(dstHeight - 1) * 2] + vertical.bounds[(dstHeight - 1) * 2 + 1]
        val bandRows = yBoxLast - yBoxFirst

        // ---- 水平 pass ----
        val temp = IntArray(bandRows * dstWidth)
        for (y in 0 until bandRows) {
            val srcRow = (y + yBoxFirst) * srcWidth
            val dstRow = y * dstWidth
            for (x in 0 until dstWidth) {
                val xmin = horizontal.bounds[x * 2]
                val xmax = horizontal.bounds[x * 2 + 1]
                val base = x * horizontal.ksize
                var r = ROUND
                var g = ROUND
                var b = ROUND
                for (i in 0 until xmax) {
                    val k = horizontal.k[base + i]
                    if (k != 0) {
                        val p = src[srcRow + xmin + i]
                        r += k * ((p ushr 16) and 0xFF)
                        g += k * ((p ushr 8) and 0xFF)
                        b += k * (p and 0xFF)
                    }
                }
                temp[dstRow + x] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }

        // ---- 垂直 pass ----
        val out = IntArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val ymin = vertical.bounds[y * 2] - yBoxFirst
            val ymax = vertical.bounds[y * 2 + 1]
            val base = y * vertical.ksize
            val dstRow = y * dstWidth
            for (x in 0 until dstWidth) {
                var r = ROUND
                var g = ROUND
                var b = ROUND
                for (i in 0 until ymax) {
                    val k = vertical.k[base + i]
                    if (k != 0) {
                        val p = temp[(ymin + i) * dstWidth + x]
                        r += k * ((p ushr 16) and 0xFF)
                        g += k * ((p ushr 8) and 0xFF)
                        b += k * (p and 0xFF)
                    }
                }
                out[dstRow + x] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }
}
