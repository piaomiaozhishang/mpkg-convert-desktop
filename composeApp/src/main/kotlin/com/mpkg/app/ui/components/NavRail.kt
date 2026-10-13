package com.mpkg.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mpkg.app.AppInfo
import com.mpkg.app.ui.theme.Motion
import com.mpkg.app.ui.theme.MpkgTheme
import com.mpkg.app.viewmodel.Screen

/**
 * 左侧导航（任务卡 T2.1）：转换 / 历史记录 / 设置 + 底部主题切换。
 *
 * 导航状态由 [com.mpkg.app.viewmodel.AppViewModel] 单一数据源持有，
 * 这里只做无状态渲染。
 */
@Composable
fun NavRail(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MpkgTheme.colors
    Column(
        modifier
            .width(NAV_WIDTH)
            .fillMaxHeight()
            .background(colors.background)
    ) {
        AppHeader()
        HairlineDivider()
        Spacer(Modifier.height(MpkgTheme.spacing.m))
        NavItem(Screen.CONVERT, AppIcons.Convert, current, onNavigate, reduceMotion)
        NavItem(Screen.HISTORY, AppIcons.History, current, onNavigate, reduceMotion)
        NavItem(Screen.SETTINGS, AppIcons.Settings, current, onNavigate, reduceMotion)
        NavItem(Screen.ABOUT, AppIcons.Info, current, onNavigate, reduceMotion)
        Spacer(Modifier.weight(1f))
        ThemeToggle(isDark = isDark, onClick = onToggleTheme)
        Spacer(Modifier.height(MpkgTheme.spacing.l))
    }
}

@Composable
private fun AppHeader() {
    val colors = MpkgTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = MpkgTheme.spacing.l,
            vertical = MpkgTheme.spacing.m
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(MpkgTheme.radius.icon))
                .background(colors.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Image,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.width(MpkgTheme.spacing.s))
        Column {
            Text(
                AppInfo.APP_NAME,
                color = colors.textPrimary,
                fontSize = MpkgTheme.fontSizes.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                "v${AppInfo.VERSION}",
                color = colors.textTertiary,
                fontSize = MpkgTheme.fontSizes.tiny,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NavItem(
    screen: Screen,
    icon: ImageVector,
    current: Screen,
    onNavigate: (Screen) -> Unit,
    reduceMotion: Boolean
) {
    val colors = MpkgTheme.colors
    val selected = screen == current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accentContainer
            hovered -> colors.surfaceVariant
            else -> colors.background
        },
        animationSpec = Motion.enter<Color>(reduceMotion),
        label = "navBackground"
    )
    val content = if (selected) colors.onAccentContainer else colors.textSecondary
    val shape = RoundedCornerShape(MpkgTheme.radius.button)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MpkgTheme.spacing.s, vertical = MpkgTheme.spacing.xs)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null) { onNavigate(screen) }
            .padding(horizontal = MpkgTheme.spacing.m, vertical = MpkgTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(MpkgTheme.spacing.m))
        Text(
            screen.title,
            color = content,
            fontSize = MpkgTheme.fontSizes.body,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ThemeToggle(isDark: Boolean, onClick: () -> Unit) {
    val colors = MpkgTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(MpkgTheme.radius.badge)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MpkgTheme.spacing.s)
            .clip(shape)
            .background(if (hovered) colors.surfaceVariant else colors.surface)
            .border(MpkgTheme.strokes.hairline, colors.border, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = MpkgTheme.spacing.m, vertical = MpkgTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDark) AppIcons.Moon else AppIcons.Sun,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(MpkgTheme.spacing.s))
        Text(
            if (isDark) "深色模式" else "浅色模式",
            color = colors.textSecondary,
            fontSize = MpkgTheme.fontSizes.tiny,
            maxLines = 1
        )
    }
}

/** 导航栏宽度（概念稿 136/600 比例在 1100 宽窗口下的取值）。 */
val NAV_WIDTH = 180.dp
