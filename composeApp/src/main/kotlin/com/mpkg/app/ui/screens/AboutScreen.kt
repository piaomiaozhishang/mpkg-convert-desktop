package com.mpkg.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mpkg.app.AppInfo
import com.mpkg.app.platform.PlatformPaths
import com.mpkg.app.ui.components.AppButton
import com.mpkg.app.ui.components.AppCard
import com.mpkg.app.ui.components.AppIcons
import com.mpkg.app.ui.components.ButtonKind
import com.mpkg.app.ui.components.HairlineDivider
import com.mpkg.app.ui.components.PageHeader
import com.mpkg.app.ui.theme.MpkgTheme

/** 关于页（任务卡 T4.2）：版本、出处、许可、已知边界，与 README 保持一致。 */
@Composable
fun AboutScreen(
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MpkgTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = MpkgTheme.spacing.xl)) {
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            PageHeader("关于", "出处、许可与版本信息")
            Spacer(Modifier.height(MpkgTheme.spacing.l))

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MpkgTheme.spacing.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(MpkgTheme.radius.button))
                                .background(colors.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.Image,
                                contentDescription = null,
                                tint = colors.onAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(MpkgTheme.spacing.m))
                        Column {
                            Text(
                                AppInfo.APP_NAME,
                                color = colors.textPrimary,
                                fontSize = MpkgTheme.fontSizes.title,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "v${AppInfo.VERSION} · ${AppInfo.LICENSE} License",
                                color = colors.textSecondary,
                                fontSize = MpkgTheme.fontSizes.tiny
                            )
                        }
                    }
                    Spacer(Modifier.height(MpkgTheme.spacing.m))
                    Text(
                        AppInfo.DESCRIPTION,
                        color = colors.textSecondary,
                        fontSize = MpkgTheme.fontSizes.body
                    )
                    Spacer(Modifier.height(MpkgTheme.spacing.s))
                    Text(
                        "项目主页：${AppInfo.PROJECT_URL}",
                        color = colors.accent,
                        fontSize = MpkgTheme.fontSizes.tiny
                    )
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.l))
            SectionTitle("许可与出处（MIT 声明原样保留）")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = MpkgTheme.spacing.l)) {
                    AppInfo.ATTRIBUTIONS.forEachIndexed { index, item ->
                        if (index > 0) HairlineDivider()
                        Column(Modifier.padding(vertical = MpkgTheme.spacing.m)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.name,
                                    color = colors.textPrimary,
                                    fontSize = MpkgTheme.fontSizes.body,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    item.license,
                                    color = colors.textTertiary,
                                    fontSize = MpkgTheme.fontSizes.tiny
                                )
                            }
                            Text(
                                item.usage,
                                color = colors.textSecondary,
                                fontSize = MpkgTheme.fontSizes.tiny
                            )
                            Text(
                                item.url,
                                color = colors.accent,
                                fontSize = MpkgTheme.fontSizes.tiny
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.l))
            SectionTitle("已知边界（照实说）")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MpkgTheme.spacing.l)) {
                    AppInfo.KNOWN_LIMITATIONS.forEach { line ->
                        Row(Modifier.padding(vertical = MpkgTheme.spacing.xs)) {
                            Text("·", color = colors.textTertiary, fontSize = MpkgTheme.fontSizes.tiny)
                            Spacer(Modifier.width(MpkgTheme.spacing.s))
                            Text(line, color = colors.textSecondary, fontSize = MpkgTheme.fontSizes.tiny)
                        }
                    }
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.l))
            SectionTitle("运行环境")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MpkgTheme.spacing.l)) {
                    Text(
                        PlatformPaths.describe(),
                        color = colors.textSecondary,
                        fontSize = MpkgTheme.fontSizes.tiny
                    )
                    Spacer(Modifier.height(MpkgTheme.spacing.xs))
                    Text(
                        "配置目录：${PlatformPaths.configDir().absolutePath}",
                        color = colors.textTertiary,
                        fontSize = MpkgTheme.fontSizes.tiny
                    )
                    Spacer(Modifier.height(MpkgTheme.spacing.m))
                    Row(horizontalArrangement = Arrangement.Start) {
                        AppButton("复制诊断信息", onCopyDiagnostics, kind = ButtonKind.SECONDARY)
                    }
                }
            }

            Spacer(Modifier.height(MpkgTheme.spacing.xl))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = MpkgTheme.colors.textTertiary,
        fontSize = MpkgTheme.fontSizes.tiny,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = MpkgTheme.spacing.s, start = MpkgTheme.spacing.xs)
    )
}
