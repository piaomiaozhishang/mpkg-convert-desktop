package com.mpkg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mpkg.app.ui.theme.MpkgTheme

/** 0.5px 细分隔线（不依赖 Material 的 Divider，避免版本差异，同时严格套用令牌）。 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, color: Color? = null) {
    Box(
        modifier
            .fillMaxWidth()
            .height(MpkgTheme.strokes.hairline)
            .background(color ?: MpkgTheme.colors.border)
    )
}

/** 竖向细分隔线。 */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(MpkgTheme.strokes.hairline)
            .background(MpkgTheme.colors.border)
    )
}

/** 卡片容器：白底 + 0.5px 描边 + 10–12 圆角，投影只用于浮层。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hoverable: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = MpkgTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(MpkgTheme.radius.cardSmall)
    Box(
        modifier
            .clip(shape)
            .background(if (hoverable && hovered) colors.surfaceVariant else colors.surface)
            .border(MpkgTheme.strokes.hairline, colors.border, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) { content() }
}

/** 主按钮 / 次按钮 / 幽灵按钮。 */
enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minWidth: Int = 0
) {
    val colors = MpkgTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        !enabled -> colors.neutralContainer
        kind == ButtonKind.PRIMARY -> if (hovered) colors.accentHover else colors.accent
        kind == ButtonKind.DANGER -> colors.dangerContainer
        kind == ButtonKind.GHOST -> Color.Transparent
        hovered -> colors.surfaceVariant
        else -> colors.surface
    }
    val contentColor = when {
        !enabled -> colors.textTertiary
        kind == ButtonKind.PRIMARY -> colors.onAccent
        kind == ButtonKind.DANGER -> colors.onDangerContainer
        else -> colors.textPrimary
    }
    val shape = RoundedCornerShape(MpkgTheme.radius.button)

    Row(
        modifier
            .then(if (minWidth > 0) Modifier.width(minWidth.dp) else Modifier)
            .clip(shape)
            .background(background)
            .then(
                if (kind == ButtonKind.PRIMARY || kind == ButtonKind.GHOST) Modifier
                else Modifier.border(MpkgTheme.strokes.hairline, colors.border, shape)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = MpkgTheme.spacing.l, vertical = MpkgTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
            Box(Modifier.width(MpkgTheme.spacing.s))
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = MpkgTheme.fontSizes.body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val iconSize = 16.dp

/** 状态徽章（全圆角）。 */
@Composable
fun StatusBadge(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(MpkgTheme.radius.badge))
            .background(container)
            .border(
                MpkgTheme.strokes.hairline,
                content.copy(alpha = 0.35f),
                RoundedCornerShape(MpkgTheme.radius.badge)
            )
            .padding(horizontal = MpkgTheme.spacing.m, vertical = MpkgTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = content, fontSize = MpkgTheme.fontSizes.tiny, maxLines = 1)
    }
}

/** 页头：标题 + 一句话说明。 */
@Composable
fun PageHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            color = MpkgTheme.colors.textPrimary,
            fontSize = MpkgTheme.fontSizes.title,
            fontWeight = FontWeight.Medium
        )
        Box(Modifier.height(MpkgTheme.spacing.xs))
        Text(
            text = subtitle,
            color = MpkgTheme.colors.textSecondary,
            fontSize = MpkgTheme.fontSizes.tiny
        )
    }
}

/** 空状态：引导文案 + 说明，不留白屏（报告 4.3 第 5 条）。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = MpkgTheme.spacing.xl * 2),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MpkgTheme.colors.textTertiary,
            modifier = Modifier.size(40.dp)
        )
        Box(Modifier.height(MpkgTheme.spacing.m))
        Text(title, color = MpkgTheme.colors.textSecondary, fontSize = MpkgTheme.fontSizes.body)
        Box(Modifier.height(MpkgTheme.spacing.xs))
        Text(
            description,
            color = MpkgTheme.colors.textTertiary,
            fontSize = MpkgTheme.fontSizes.tiny,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Box(Modifier.height(MpkgTheme.spacing.l))
            action()
        }
    }
}

/** 带内边距的横向行，供设置项复用。 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = MpkgTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = MpkgTheme.spacing.l)) {
            Text(title, color = MpkgTheme.colors.textPrimary, fontSize = MpkgTheme.fontSizes.body)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    color = MpkgTheme.colors.textTertiary,
                    fontSize = MpkgTheme.fontSizes.tiny
                )
            }
        }
        control()
    }
}

/** 供设置页使用的内边距常量。 */
val CardPadding = PaddingValues(
    horizontal = MpkgTheme.spacing.l,
    vertical = MpkgTheme.spacing.m
)
