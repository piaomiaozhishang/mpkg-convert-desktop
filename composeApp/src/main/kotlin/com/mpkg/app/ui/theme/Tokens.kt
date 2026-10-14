package com.mpkg.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（任务卡 T3.1）。
 *
 * 对应《桌面版递交报告》4.1 节的规范；所有组件只能引用这里的令牌，
 * **禁止在任何 UI 代码里写死色值、圆角或间距**。
 */

/** 间距五档。 */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** 圆角。 */
object Radius {
    val card: Dp = 12.dp
    val cardSmall: Dp = 10.dp
    val button: Dp = 8.dp
    val icon: Dp = 5.dp
    val badge: Dp = 999.dp
    val window: Dp = 12.dp
}

/** 字号四级。 */
object FontSizes {
    val tiny = 12.sp
    val body = 13.sp
    val title = 15.sp
    val display = 20.sp
}

/** 细线/描边宽度。 */
object Strokes {
    /** 0.5px 细分隔线（见报告 4.1「边框」）。 */
    val hairline: Dp = 0.5.dp
    val normal: Dp = 1.dp
    val emphasis: Dp = 1.25.dp
}

/** 主色预设：报告要求「主色 1 个（建议靛蓝系），提供 3–4 套预设」。 */
enum class AccentPreset(val displayName: String, val seed: Color) {
    /** 默认：概念稿里的经典蓝。 */
    CLASSIC_BLUE("经典蓝", Color(0xFF185FA5)),
    INDIGO("靛蓝", Color(0xFF4F46E5)),
    TEAL("青碧", Color(0xFF0F766E)),
    VIOLET("紫罗兰", Color(0xFF7C3AED)),
    AMBER("琥珀", Color(0xFFB45309));

    companion object {
        val DEFAULT = CLASSIC_BLUE
    }
}

/** 主题模式。 */
enum class ThemeMode(val displayName: String) {
    /** 壁纸工具默认深色（报告 4.1）。 */
    DARK("深色"),
    LIGHT("浅色"),
    SYSTEM("跟随系统");

    companion object {
        val DEFAULT = DARK

        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 一套完整的语义色。所有字段都是令牌，UI 不得绕过。
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentHover: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentBorder: Color,
    val onAccent: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val scrim: Color,
    val progressTrack: Color
) {
    /** 供 T3.1 验收脚本逐对检查「正文对比度不低于 4.5:1」。 */
    fun contrastPairs(): List<Triple<String, Color, Color>> = listOf(
        Triple("正文/背景", textPrimary, background),
        Triple("正文/卡片", textPrimary, surface),
        Triple("次要文字/背景", textSecondary, background),
        Triple("次要文字/卡片", textSecondary, surface),
        Triple("三级文字/卡片", textTertiary, surface),
        Triple("主色文字/背景", accent, background),
        Triple("主色文字/卡片", accent, surface),
        Triple("主色按钮文字/主色", onAccent, accent),
        Triple("成功文字/成功底色", onSuccessContainer, successContainer),
        Triple("危险文字/危险底色", onDangerContainer, dangerContainer),
        Triple("中性文字/中性底色", onNeutralContainer, neutralContainer),
        Triple("主色底色文字/主色底色", onAccentContainer, accentContainer)
    )
}
