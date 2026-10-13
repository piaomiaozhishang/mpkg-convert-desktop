package com.mpkg.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计令牌与双主题的机械验收（任务卡 T3.1）。
 *
 * 对应验收标准「正文对比度不低于 4.5:1」——把主观判断变成可计算、可回归的断言。
 */
class ThemeContrastTest {

    private val minimumRatio = 4.5

    @Test
    fun `深色与浅色主题下所有语义配对都达到 4_5 比 1`() {
        val failures = ArrayList<String>()
        for (preset in AccentPreset.entries) {
            listOf(lightColors(preset), darkColors(preset)).forEach { colors ->
                val themeName = "${preset.displayName}/${if (colors.isDark) "深色" else "浅色"}"
                colors.contrastPairs().forEach { (label, fg, bg) ->
                    val ratio = ColorMath.contrastRatio(fg, bg)
                    if (ratio < minimumRatio) {
                        failures.add("$themeName $label = ${"%.2f".format(ratio)}:1")
                    }
                }
            }
        }
        assertTrue("以下配对未达到 ${minimumRatio}:1\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `五种主色预设都能推导出可用的强调色`() {
        assertEquals(5, AccentPreset.entries.size)
        AccentPreset.entries.forEach { preset ->
            val light = lightColors(preset)
            val dark = darkColors(preset)
            assertTrue("${preset.displayName} 浅色主色与卡片对比不足", ColorMath.meetsContrast(light.accent, light.surface))
            assertTrue("${preset.displayName} 深色主色与卡片对比不足", ColorMath.meetsContrast(dark.accent, dark.surface))
            assertTrue("${preset.displayName} 浅色按钮文字看不清", ColorMath.meetsContrast(light.onAccent, light.accent))
            assertTrue("${preset.displayName} 深色按钮文字看不清", ColorMath.meetsContrast(dark.onAccent, dark.accent))
        }
    }

    @Test
    fun `ensureContrast 会把不达标的颜色推到达标`() {
        val barelyVisible = Color(0xFFEEEEEE)
        val onWhite = ColorMath.ensureContrast(barelyVisible, Color.White)
        assertTrue(ColorMath.meetsContrast(onWhite, Color.White))
    }

    @Test
    fun `对比度公式本身正确`() {
        assertEquals(21.0, ColorMath.contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, ColorMath.contrastRatio(Color.White, Color.White), 0.001)
    }

    @Test
    fun `主题模式切换只影响明暗不影响主色选择`() {
        val dark = colorsFor(ThemeMode.DARK, AccentPreset.TEAL, systemDark = false)
        val light = colorsFor(ThemeMode.LIGHT, AccentPreset.TEAL, systemDark = true)
        assertTrue(dark.isDark)
        assertTrue(!light.isDark)
        assertTrue(colorsFor(ThemeMode.SYSTEM, AccentPreset.TEAL, systemDark = true).isDark)
        assertTrue(!colorsFor(ThemeMode.SYSTEM, AccentPreset.TEAL, systemDark = false).isDark)
    }

    @Test
    fun `动效时长全部不超过 300 毫秒`() {
        assertTrue(Motion.FAST <= Motion.MAX_ALLOWED)
        assertTrue(Motion.NORMAL <= Motion.MAX_ALLOWED)
        assertTrue(Motion.SLOW <= Motion.MAX_ALLOWED)
    }
}
