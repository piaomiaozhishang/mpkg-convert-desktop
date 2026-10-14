package com.mpkg.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 颜色计算工具（任务卡 T3.1）。
 *
 * 用于从「主色种子」推导整套语义色，并把「正文对比度不低于 4.5:1」变成可计算、
 * 可单元测试的约束——而不是靠肉眼挑颜色。
 *
 * 公式来自 WCAG 2.1：相对亮度用 sRGB 线性化后的加权和，对比度为 (L1+0.05)/(L2+0.05)。
 */
object ColorMath {

    /** sRGB 单通道线性化。 */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** 相对亮度（0 = 黑，1 = 白）。 */
    fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    /** 两个颜色的对比度，范围 1.0 ~ 21.0。 */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 线性插值混色；[amount] 为 [other] 的占比（0 = 全 [this]，1 = 全 [other]）。 */
    fun Color.mix(other: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            red = red + (other.red - red) * t,
            green = green + (other.green - green) * t,
            blue = blue + (other.blue - blue) * t,
            alpha = alpha + (other.alpha - alpha) * t
        )
    }

    /** 以固定步长把 [foreground] 往黑或白推，直到与 [background] 的对比度达到 [minRatio]。 */
    fun ensureContrast(foreground: Color, background: Color, minRatio: Double = 4.5): Color {
        if (contrastRatio(foreground, background) >= minRatio) return foreground
        val toward = if (relativeLuminance(background) > 0.5) Color.Black else Color.White
        var step = 0.02f
        while (step <= 1f) {
            val candidate = foreground.mix(toward, step)
            if (contrastRatio(candidate, background) >= minRatio) return candidate
            step += 0.02f
        }
        return toward
    }

    /** 判断两色对比度是否达标（供单元测试与调试断言使用）。 */
    fun meetsContrast(foreground: Color, background: Color, minRatio: Double = 4.5): Boolean =
        contrastRatio(foreground, background) >= minRatio

    /** 感知上的「浅色」判断，用于决定往黑还是往白推。 */
    fun isLight(color: Color): Boolean = relativeLuminance(color) > 0.5

    /** 供日志/测试输出用的对比度文本。 */
    fun describe(a: Color, b: Color): String =
        String.format("%.2f:1", contrastRatio(a, b))

    /** 两个颜色是否近似相等（用于避免无谓重组）。 */
    fun approxEquals(a: Color, b: Color, tolerance: Float = 0.001f): Boolean =
        abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance &&
            abs(a.blue - b.blue) < tolerance && abs(a.alpha - b.alpha) < tolerance
}
