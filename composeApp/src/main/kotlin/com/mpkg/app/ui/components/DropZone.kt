package com.mpkg.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mpkg.app.ui.theme.Motion
import com.mpkg.app.ui.theme.MpkgTheme

/**
 * 拖拽区（任务卡 T2.2，对应概念稿的虚线框）。
 *
 * 虚线 + 悬停高亮用 `drawBehind` 手绘，避免依赖并不存在的「虚线边框」组件；
 * 动效时长走 [Motion] 令牌。
 */
@Composable
fun DropZone(
    active: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MpkgTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val highlight by animateColorAsState(
        targetValue = if (active) colors.accent else if (hovered && enabled) colors.accentBorder else colors.border,
        animationSpec = Motion.enter(reduceMotion),
        label = "dropZoneBorder"
    )
    val background by animateColorAsState(
        targetValue = if (active) colors.accentContainer else colors.surface,
        animationSpec = Motion.enter(reduceMotion),
        label = "dropZoneBackground"
    )
    val lift by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = Motion.enter(reduceMotion),
        label = "dropZoneLift"
    )

    val shape = RoundedCornerShape(MpkgTheme.radius.card)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onPick
            )
            .drawBehind {
                val inset = 1.25f + lift * 0.75f
                drawRoundRect(
                    color = highlight,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = (1.25f + lift * 0.75f).dp.toPx(), pathEffect = dash)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(MpkgTheme.radius.badge))
                    .background(if (active) colors.accent else colors.surface)
                    .drawBehind {
                        // 圆形描边
                        drawCircle(
                            color = colors.accentBorder,
                            radius = size.minDimension / 2f - 0.5f,
                            style = Stroke(width = 0.75.dp.toPx())
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Convert,
                    contentDescription = null,
                    tint = if (active) colors.onAccent else colors.accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.height(MpkgTheme.spacing.m))
            Text(
                text = if (active) "松开即加入队列" else "拖入 .mpkg / .pkg 文件",
                color = if (active) colors.onAccentContainer else colors.textPrimary,
                fontSize = MpkgTheme.fontSizes.body,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(MpkgTheme.spacing.xs))
            Text(
                text = if (enabled) "或点击选择 · 支持多选批量" else "队列处理中，完成后可继续添加",
                color = colors.textSecondary,
                fontSize = MpkgTheme.fontSizes.tiny
            )
        }
    }
}

/** 支持格式说明，放在拖拽区下方（空状态不留白屏）。 */
@Composable
fun SupportedFormatsHint(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "支持：.mpkg（手机端）/ .pkg、scene.pkg（桌面端工程）· 输出 4K 原图 PNG 或 MP4（字节导出，不重编码）",
            color = MpkgTheme.colors.textTertiary,
            fontSize = MpkgTheme.fontSizes.tiny
        )
    }
}
