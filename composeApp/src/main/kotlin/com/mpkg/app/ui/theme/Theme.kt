package com.mpkg.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mpkg.app.ui.theme.ColorMath.ensureContrast
import com.mpkg.app.ui.theme.ColorMath.mix

/**
 * 双主题与主色预设（任务卡 T3.1）。
 *
 * 令牌是唯一来源：UI 通过 `MpkgTheme.colors` / `MpkgTheme.spacing` 取值，
 * 任何组件里出现字面色值都视为违规（见《03 · 开发规范》第 3 节第 6 条）。
 */
object MpkgTheme {

    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current

    val spacing = Spacing
    val radius = Radius
    val fontSizes = FontSizes
    val strokes = Strokes
}

internal val LocalAppColors: ProvidableCompositionLocal<AppColors> =
    staticCompositionLocalOf { lightColors(AccentPreset.DEFAULT) }

/** 浅色主题色板（取值对齐《桌面版GUI概念稿.svg》）。 */
fun lightColors(accent: AccentPreset): AppColors {
    val background = Color(0xFFF1EFE8)
    val surface = Color(0xFFFFFFFF)
    val surfaceVariant = Color(0xFFF7F6F2)
    val border = Color(0xFFD3D1C7)
    val borderStrong = Color(0xFFB4B2A9)
    val textPrimary = Color(0xFF2C2C2A)
    val textSecondary = Color(0xFF5F5E5A)
    // 三级文字（辅助说明）同样按 4.5:1 收敛：原设计色 #8A8880 在卡片上只有 3.55:1。
    val textTertiary = ensureContrast(Color(0xFF8A8880), surface, 4.5)

    // 主色要同时压过卡片（白）与页面底色（米白）这两处背景，取更严的那个；
    // 琥珀色只按白底收敛时在页面底色上只有 4.36:1。
    val accentOn = ensureContrast(ensureContrast(accent.seed, surface, 4.5), background, 4.5)
    val accentContainer = accent.seed.mix(surface, 0.90f)
    val successContainer = Color(0xFFE1F5EE)
    val dangerContainer = Color(0xFFFCEBE9)
    val neutralContainer = Color(0xFFEFEEE8)

    return AppColors(
        isDark = false,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        border = border,
        borderStrong = borderStrong,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accent = accentOn,
        accentHover = accentOn.mix(Color.Black, 0.14f),
        accentContainer = accentContainer,
        onAccentContainer = ensureContrast(accent.seed, accentContainer, 4.5),
        accentBorder = accent.seed.mix(surface, 0.45f),
        onAccent = ensureContrast(surface, accentOn, 4.5),
        success = Color(0xFF0F6E4A),
        successContainer = successContainer,
        onSuccessContainer = ensureContrast(Color(0xFF0F6E4A), successContainer, 4.5),
        danger = Color(0xFFA32B22),
        dangerContainer = dangerContainer,
        onDangerContainer = ensureContrast(Color(0xFFA32B22), dangerContainer, 4.5),
        neutral = Color(0xFF5F5E5A),
        neutralContainer = neutralContainer,
        onNeutralContainer = ensureContrast(Color(0xFF5F5E5A), neutralContainer, 4.5),
        scrim = Color(0x66000000),
        progressTrack = Color(0xFFE6E4DC)
    )
}

/** 深色主题色板（壁纸工具默认主题）。 */
fun darkColors(accent: AccentPreset): AppColors {
    val background = Color(0xFF101110)
    val surface = Color(0xFF1A1B19)
    val surfaceVariant = Color(0xFF232522)
    val border = Color(0xFF2E302C)
    val borderStrong = Color(0xFF41443E)
    val textPrimary = Color(0xFFEDEDE7)
    val textSecondary = Color(0xFFA9A89F)
    // 深色下 #7E7D75 在卡片上只有 4.18:1，同样收敛到 4.5:1。
    val textTertiary = ensureContrast(Color(0xFF7E7D75), surface, 4.5)

    val accentOn = ensureContrast(ensureContrast(accent.seed, surface, 4.5), background, 4.5)
    val accentContainer = accent.seed.mix(surface, 0.78f)
    val successContainer = Color(0xFF10301F)
    val dangerContainer = Color(0xFF3A1714)
    val neutralContainer = Color(0xFF26271F)

    return AppColors(
        isDark = true,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        border = border,
        borderStrong = borderStrong,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accent = accentOn,
        accentHover = accentOn.mix(Color.White, 0.16f),
        accentContainer = accentContainer,
        onAccentContainer = ensureContrast(accent.seed.mix(Color.White, 0.55f), accentContainer, 4.5),
        accentBorder = accent.seed.mix(surface, 0.55f),
        onAccent = ensureContrast(surface, accentOn, 4.5),
        success = Color(0xFF6FD3A8),
        successContainer = successContainer,
        onSuccessContainer = ensureContrast(Color(0xFF6FD3A8), successContainer, 4.5),
        danger = Color(0xFFF2938B),
        dangerContainer = dangerContainer,
        onDangerContainer = ensureContrast(Color(0xFFF2938B), dangerContainer, 4.5),
        neutral = Color(0xFFA9A89F),
        neutralContainer = neutralContainer,
        onNeutralContainer = ensureContrast(Color(0xFFA9A89F), neutralContainer, 4.5),
        scrim = Color(0x99000000),
        progressTrack = Color(0xFF2B2D29)
    )
}

/** 按模式与主色取色板。 */
fun colorsFor(mode: ThemeMode, accent: AccentPreset, systemDark: Boolean): AppColors {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    return if (dark) darkColors(accent) else lightColors(accent)
}

private fun appTypography(): Typography {
    val base = Typography()
    val family = FontFamily.SansSerif
    return base.copy(
        displaySmall = TextStyle(fontFamily = family, fontSize = FontSizes.display, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = family, fontSize = FontSizes.title, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontFamily = family, fontSize = FontSizes.body),
        bodySmall = TextStyle(fontFamily = family, fontSize = FontSizes.tiny),
        labelSmall = TextStyle(fontFamily = family, fontSize = FontSizes.tiny)
    )
}

/** 主题根：注入令牌并把 Material3 配色对齐到同一套语义色。 */
@Composable
fun MpkgTheme(
    mode: ThemeMode,
    accent: AccentPreset,
    systemDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = colorsFor(mode, accent, systemDark)
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.danger,
            onError = colors.onDangerContainer,
            errorContainer = colors.dangerContainer,
            onErrorContainer = colors.onDangerContainer
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.danger,
            onError = colors.onDangerContainer,
            errorContainer = colors.dangerContainer,
            onErrorContainer = colors.onDangerContainer
        )
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = appTypography(), content = content)
    }
}
