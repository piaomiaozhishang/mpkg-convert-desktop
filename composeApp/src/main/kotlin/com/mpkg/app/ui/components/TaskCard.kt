package com.mpkg.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mpkg.app.queue.ConversionTask
import com.mpkg.app.queue.TaskStatus
import com.mpkg.app.ui.theme.Motion
import com.mpkg.app.ui.theme.MpkgTheme

/**
 * 任务卡片（任务卡 T2.3，对应概念稿的卡片列表项）。
 *
 * 纯展示 + 回调，状态由队列推送；缩略图直接来自包内 preview.jpg 的字节，
 * 不解码任何纹理、不加载整个包。
 */
@Composable
fun TaskCard(
    task: ConversionTask,
    reduceMotion: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MpkgTheme.colors
    val shape = RoundedCornerShape(MpkgTheme.radius.cardSmall)
    val doubleTap = remember(task.id) {
        Modifier.pointerInput(task.id) {
            detectTapGestures(
                onTap = { onSelect() },
                onDoubleTap = { if (task.status == TaskStatus.DONE) onOpen() }
            )
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                width = if (selected) MpkgTheme.strokes.normal else MpkgTheme.strokes.hairline,
                color = if (selected) colors.accent else colors.border,
                shape = shape
            )
            .then(doubleTap)
    ) {
        Row(Modifier.padding(MpkgTheme.spacing.m), verticalAlignment = Alignment.Top) {
            Thumbnail(task)
            Spacer(Modifier.width(MpkgTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.fileName,
                        color = colors.textPrimary,
                        fontSize = MpkgTheme.fontSizes.body,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (task.status == TaskStatus.RUNNING) {
                        Spacer(Modifier.width(MpkgTheme.spacing.s))
                        Text(
                            "${(task.progress * 100).toInt()}%",
                            color = colors.accent,
                            fontSize = MpkgTheme.fontSizes.tiny
                        )
                    }
                }
                Spacer(Modifier.height(MpkgTheme.spacing.xs))
                Text(
                    metaLine(task),
                    color = colors.textSecondary,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.status == TaskStatus.RUNNING) {
                    Spacer(Modifier.height(MpkgTheme.spacing.s))
                    ProgressBar(task.progress, reduceMotion)
                    Spacer(Modifier.height(MpkgTheme.spacing.xs))
                    Text(
                        task.stage,
                        color = colors.textTertiary,
                        fontSize = MpkgTheme.fontSizes.tiny,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                task.errorTitle?.let { title ->
                    Spacer(Modifier.height(MpkgTheme.spacing.s))
                    Text(
                        title,
                        color = colors.onDangerContainer,
                        fontSize = MpkgTheme.fontSizes.tiny,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(MpkgTheme.spacing.m))
            Column(horizontalAlignment = Alignment.End) {
                StatusBadgeFor(task.status)
                Spacer(Modifier.height(MpkgTheme.spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        TaskStatus.WAITING -> CardAction(AppIcons.Close, "移出列表", onRemove)
                        TaskStatus.RUNNING -> CardAction(AppIcons.Stop, "取消", onCancel)
                        TaskStatus.DONE -> {
                            CardAction(AppIcons.ExternalOpen, "打开输出文件", onOpen)
                            CardAction(AppIcons.Folder, "在文件夹中显示", onReveal)
                            CardAction(AppIcons.Close, "移出列表", onRemove)
                        }
                        TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                            CardAction(AppIcons.Refresh, "重试", onRetry)
                            CardAction(AppIcons.Close, "移出列表", onRemove)
                        }
                    }
                }
            }
        }
    }
}

private fun metaLine(task: ConversionTask): String {
    val parts = ArrayList<String>(3)
    parts.add(task.packageSummary())
    task.outputSummary()?.let { parts.add(it) }
    if (task.status == TaskStatus.DONE && task.elapsedMs > 0) {
        parts.add("用时 ${"%.1f".format(task.elapsedMs / 1000.0)} 秒")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Thumbnail(task: ConversionTask) {
    val colors = MpkgTheme.colors
    val shape = RoundedCornerShape(MpkgTheme.radius.icon)
    val bitmap: ImageBitmap? = remember(task.thumbnailBytes) {
        task.thumbnailBytes?.let { bytes ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(MpkgTheme.strokes.hairline, colors.border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )
        } else {
            PlaceholderArtwork(task)
        }
    }
}

/** 没有 preview 时画一个占位图形，而不是留白。 */
@Composable
private fun PlaceholderArtwork(task: ConversionTask) {
    val colors = MpkgTheme.colors
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        Box(
            Modifier.fillMaxWidth().height(52.dp).drawBehind {
                val w = size.width
                val h = size.height
                // 远处的「山」与一轮圆点，示意这是一张图片
                drawCircle(
                    color = colors.borderStrong,
                    radius = 2.5.dp.toPx(),
                    center = Offset(w * 0.28f, h * 0.32f)
                )
                val path = Path().apply {
                    moveTo(0f, h * 0.92f)
                    lineTo(w * 0.30f, h * 0.48f)
                    lineTo(w * 0.52f, h * 0.72f)
                    lineTo(w * 0.68f, h * 0.52f)
                    lineTo(w, h * 0.92f)
                    close()
                }
                drawPath(path, color = colors.borderStrong)
            }
        )
        Icon(
            if (task.isVideo) AppIcons.Video else AppIcons.Image,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(12.dp)
        )
    }
}

@Composable
private fun ProgressBar(progress: Float, reduceMotion: Boolean) {
    val colors = MpkgTheme.colors
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.progress(reduceMotion),
        label = "taskProgress"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(MpkgTheme.radius.badge))
            .background(colors.progressTrack)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceAtLeast(0.02f))
                .height(6.dp)
                .clip(RoundedCornerShape(MpkgTheme.radius.badge))
                .background(colors.accent)
        )
    }
}

@Composable
private fun StatusBadgeFor(status: TaskStatus) {
    val colors = MpkgTheme.colors
    val (text, container, content) = when (status) {
        TaskStatus.WAITING -> Triple("等待中", colors.neutralContainer, colors.onNeutralContainer)
        TaskStatus.RUNNING -> Triple("解码中", colors.accentContainer, colors.onAccentContainer)
        TaskStatus.DONE -> Triple("已完成", colors.successContainer, colors.onSuccessContainer)
        TaskStatus.FAILED -> Triple("失败", colors.dangerContainer, colors.onDangerContainer)
        TaskStatus.CANCELLED -> Triple("已取消", colors.neutralContainer, colors.onNeutralContainer)
    }
    StatusBadge(text, container, content)
}

@Composable
private fun CardAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = MpkgTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .padding(start = MpkgTheme.spacing.xs)
            .size(24.dp)
            .clip(RoundedCornerShape(MpkgTheme.radius.icon))
            .background(if (hovered) colors.surfaceVariant else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
    }
}
