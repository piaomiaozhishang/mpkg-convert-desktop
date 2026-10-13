package com.mpkg.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mpkg.app.export.OverwritePolicy
import com.mpkg.app.settings.AppSettings
import com.mpkg.app.settings.DecodeMode
import com.mpkg.app.ui.components.AppButton
import com.mpkg.app.ui.components.AppCard
import com.mpkg.app.ui.components.AppIcons
import com.mpkg.app.ui.components.ButtonKind
import com.mpkg.app.ui.components.HairlineDivider
import com.mpkg.app.ui.components.PageHeader
import com.mpkg.app.ui.components.SettingRow
import com.mpkg.app.ui.theme.AccentPreset
import com.mpkg.app.ui.theme.MpkgTheme
import com.mpkg.app.ui.theme.ThemeMode

/** 设置页（任务卡 T3.4）。每项改动即时生效并持久化。 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onChooseOutputDir: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MpkgTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = MpkgTheme.spacing.xl)) {
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            PageHeader("设置", "所有改动即时生效并自动保存")
            Spacer(Modifier.height(MpkgTheme.spacing.l))

            SectionCard("输出") {
                SettingRow("输出目录", settings.outputDir) {
                    AppButton("更改", onChooseOutputDir, kind = ButtonKind.SECONDARY)
                }
                HairlineDivider()
                SettingRow("同名文件", "输出目录里已有同名文件时怎么做") {
                    SegmentedChoice(
                        options = listOf(
                            OverwritePolicy.RENAME to "重命名",
                            OverwritePolicy.OVERWRITE to "覆盖",
                            OverwritePolicy.SKIP to "跳过"
                        ),
                        selected = settings.overwritePolicy,
                        onSelect = { policy -> onChange { it.copy(overwritePolicy = policy) } }
                    )
                }
                HairlineDivider()
                SettingRow("同时导出 preview.jpg", "包内自带的预览缩略图，不是原图") {
                    AppSwitch(settings.exportPreview) { value -> onChange { it.copy(exportPreview = value) } }
                }
                HairlineDivider()
                SettingRow("完成后打开输出目录", "转换全部结束后自动打开文件夹") {
                    AppSwitch(settings.openOutputWhenDone) { value -> onChange { it.copy(openOutputWhenDone = value) } }
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.l))

            SectionCard("解码") {
                SettingRow("解码方式", "CPU 解码 4K 单张约 1–3 秒；GPU 版本尚未实现，仅占位") {
                    Column {
                        DecodeMode.entries.forEach { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MpkgTheme.radius.button))
                                    .clickable(enabled = mode.available) {
                                        onChange { it.copy(decodeMode = mode) }
                                    }
                                    .padding(end = MpkgTheme.spacing.s)
                            ) {
                                RadioButton(
                                    selected = settings.decodeMode == mode,
                                    onClick = { if (mode.available) onChange { it.copy(decodeMode = mode) } },
                                    enabled = mode.available,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = colors.accent,
                                        unselectedColor = colors.borderStrong,
                                        disabledSelectedColor = colors.borderStrong,
                                        disabledUnselectedColor = colors.border
                                    )
                                )
                                Text(
                                    mode.displayName,
                                    color = if (mode.available) colors.textSecondary else colors.textTertiary,
                                    fontSize = MpkgTheme.fontSizes.tiny
                                )
                            }
                        }
                    }
                }
                HairlineDivider()
                SettingRow(
                    "并发解码数：${settings.concurrency}",
                    "同时处理几个文件；解码本身是 CPU 密集的，机器一般 2–4 比较合适"
                ) {
                    Slider(
                        value = settings.concurrency.toFloat(),
                        onValueChange = { value -> onChange { it.copy(concurrency = value.toInt()) } },
                        valueRange = AppSettings.MIN_CONCURRENCY.toFloat()..AppSettings.MAX_CONCURRENCY.toFloat(),
                        steps = AppSettings.MAX_CONCURRENCY - AppSettings.MIN_CONCURRENCY - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.progressTrack
                        ),
                        modifier = Modifier.width(180.dp)
                    )
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.l))

            SectionCard("外观") {
                SettingRow("主题", "壁纸工具默认深色；也可以跟随系统") {
                    SegmentedChoice(
                        options = ThemeMode.entries.map { it to it.displayName },
                        selected = settings.themeMode,
                        onSelect = { mode -> onChange { it.copy(themeMode = mode) } }
                    )
                }
                HairlineDivider()
                SettingRow("主色", "全局强调色，所有组件都走同一套令牌") {
                    Row(horizontalArrangement = Arrangement.spacedBy(MpkgTheme.spacing.s)) {
                        AccentPreset.entries.forEach { preset ->
                            AccentSwatch(
                                preset = preset,
                                selected = settings.accent == preset,
                                onClick = { onChange { it.copy(accent = preset) } }
                            )
                        }
                    }
                }
                HairlineDivider()
                SettingRow("减少动态效果", "关闭全部过渡动画（单个动效本就 ≤ 300ms）") {
                    AppSwitch(settings.reduceMotion) { value -> onChange { it.copy(reduceMotion = value) } }
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.xl))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            color = MpkgTheme.colors.textTertiary,
            fontSize = MpkgTheme.fontSizes.tiny,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = MpkgTheme.spacing.s, start = MpkgTheme.spacing.xs)
        )
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = MpkgTheme.spacing.l, vertical = MpkgTheme.spacing.xs)) {
                content()
            }
        }
    }
}

@Composable
private fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MpkgTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textTertiary,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.borderStrong
        )
    )
}

/** 轻量分段选择器（替代下拉，减少一次点击）。 */
@Composable
private fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    val colors = MpkgTheme.colors
    val shape = RoundedCornerShape(MpkgTheme.radius.button)
    Row(
        Modifier
            .clip(shape)
            .border(MpkgTheme.strokes.hairline, colors.border, shape)
    ) {
        options.forEachIndexed { index, (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .background(if (active) colors.accentContainer else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = MpkgTheme.spacing.m, vertical = MpkgTheme.spacing.s)
            ) {
                Text(
                    label,
                    color = if (active) colors.onAccentContainer else colors.textSecondary,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index != options.lastIndex) {
                Box(
                    Modifier
                        .width(MpkgTheme.strokes.hairline)
                        .height(20.dp)
                        .background(colors.border)
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(preset: AccentPreset, selected: Boolean, onClick: () -> Unit) {
    val colors = MpkgTheme.colors
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(MpkgTheme.radius.badge))
            .background(preset.seed)
            .border(
                width = if (selected) 2.dp else MpkgTheme.strokes.hairline,
                color = if (selected) colors.textPrimary else colors.border,
                shape = RoundedCornerShape(MpkgTheme.radius.badge)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                AppIcons.Check,
                contentDescription = preset.displayName,
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}
