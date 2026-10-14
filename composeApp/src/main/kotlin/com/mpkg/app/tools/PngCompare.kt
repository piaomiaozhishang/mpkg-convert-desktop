package com.mpkg.app.tools

import com.mpkg.app.image.LanczosResampler
import com.mpkg.app.image.PngIo
import java.io.File

/**
 * PNG 逐像素比对工具（任务卡 T1.3，接口与《04 · 测试与验收指南》第 4 节一致）。
 *
 * 用法：
 * ```
 * PngCompare <a.png> <b.png>
 * ```
 * 尺寸一致时直接逐像素比；尺寸不一致但成整数倍时，先用 [LanczosResampler]
 * 把大图缩到小图尺寸再比——本项目提供的 `sample/expected_1280x720.png`
 * 就是 4K 解码结果经 Pillow Lanczos 缩小得到的，因此这条路径能给出 0 差异结论。
 *
 * 退出码：0 = PASS，1 = FAIL，2 = 用法或读图错误。
 */
object PngCompare {

    class Result(
        val width: Int,
        val height: Int,
        val diffPixels: Long,
        val maxChannelDelta: Int,
        val resampled: Boolean
    ) {
        val pass: Boolean get() = diffPixels == 0L
    }

    fun compare(fileA: File, fileB: File): Result {
        val a = PngIo.read(fileA) ?: throw IllegalArgumentException("无法读取图片: $fileA")
        val b = PngIo.read(fileB) ?: throw IllegalArgumentException("无法读取图片: $fileB")

        val (ref, other, resampled) = when {
            a.width == b.width && a.height == b.height -> Triple(a, b, false)
            a.width % b.width == 0 && a.height % b.height == 0 -> Triple(
                com.mpkg.app.image.Raster(
                    b.width, b.height,
                    LanczosResampler.resample(a.argb, a.width, a.height, b.width, b.height)
                ),
                b, true
            )
            b.width % a.width == 0 && b.height % a.height == 0 -> Triple(
                a,
                com.mpkg.app.image.Raster(
                    a.width, a.height,
                    LanczosResampler.resample(b.argb, b.width, b.height, a.width, a.height)
                ),
                true
            )
            else -> throw IllegalArgumentException(
                "尺寸不一致且不是整数倍: ${a.width}x${a.height} vs ${b.width}x${b.height}"
            )
        }

        var diff = 0L
        var maxDelta = 0
        for (i in 0 until ref.width * ref.height) {
            val pa = ref.rgbAt(i)
            val pb = other.rgbAt(i)
            if (pa != pb) {
                diff++
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = Math.abs(((pa ushr shift) and 0xFF) - ((pb ushr shift) and 0xFF))
                    if (d > maxDelta) maxDelta = d
                }
            }
        }
        return Result(ref.width, ref.height, diff, maxDelta, resampled)
    }

    /** CLI 入口；返回进程退出码。 */
    fun run(args: Array<String>): Int {
        if (args.size != 2) {
            System.out.println("用法: PngCompare <a.png> <b.png>")
            return 2
        }
        return try {
            val r = compare(File(args[0]), File(args[1]))
            val total = r.width.toLong() * r.height
            val extra = if (r.resampled) "（已按整数因子 Lanczos 缩放对齐）" else ""
            System.out.println("差异像素=${r.diffPixels} / $total, 最大通道差=${r.maxChannelDelta}$extra")
            System.out.println(if (r.pass) "PASS: 逐像素一致" else "FAIL")
            if (r.pass) 0 else 1
        } catch (e: Exception) {
            System.out.println("比对失败: ${e.message}")
            2
        }
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(PngCompare.run(args))
}
