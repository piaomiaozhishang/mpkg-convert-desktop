package com.mpkg.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mpkg.app.history.HistoryEntry
import com.mpkg.app.ui.components.AppButton
import com.mpkg.app.ui.components.AppCard
import com.mpkg.app.ui.components.AppIcons
import com.mpkg.app.ui.components.ButtonKind
import com.mpkg.app.ui.components.EmptyState
import com.mpkg.app.ui.components.PageHeader
import com.mpkg.app.ui.components.StatusBadge
import com.mpkg.app.ui.theme.MpkgTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史记录页（任务卡 T3.3）。 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    thumbnails: Map<String, ByteArray>,
    onOpen: (HistoryEntry) -> Unit,
    onReveal: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = MpkgTheme.spacing.xl)) {
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PageHeader("历史记录", "重启后依然保留；点击可定位输出文件", Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    AppButton("清空历史", onClear, kind = ButtonKind.SECONDARY)
                }
            }
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            if (entries.isEmpty()) {
                EmptyState(
                    icon = AppIcons.History,
                    title = "还没有转换记录",
                    description = "完成一次转换后，这里会列出文件名、类型、时间与输出路径。\n" +
                        "缩略图缓存有数量上限，超过后按最近使用时间自动淘汰。"
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MpkgTheme.spacing.s),
                    contentPadding = PaddingValues(bottom = MpkgTheme.spacing.l)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(entry, thumbnails[entry.id], onOpen, onReveal)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    thumbnail: ByteArray?,
    onOpen: (HistoryEntry) -> Unit,
    onReveal: (HistoryEntry) -> Unit
) {
    val colors = MpkgTheme.colors
    val exists = entry.outputExists()
    val bitmap: ImageBitmap? = remember(thumbnail) {
        thumbnail?.let { bytes ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }

    AppCard(
        Modifier.fillMaxWidth(),
        onClick = { onOpen(entry) },
        hoverable = true
    ) {
        Row(Modifier.padding(MpkgTheme.spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Thumb(bitmap, entry.kind)
            Spacer(Modifier.width(MpkgTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    color = colors.textPrimary,
                    fontSize = MpkgTheme.fontSizes.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(MpkgTheme.spacing.xs))
                Text(
                    "${formatTime(entry.timestamp)} · ${if (entry.kind == "video") "视频" else "图片"} · ${entry.note ?: ""}",
                    color = colors.textSecondary,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(MpkgTheme.spacing.xs))
                Text(
                    entry.primaryOutput ?: "（无输出路径）",
                    color = if (exists) colors.textTertiary else colors.danger,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(MpkgTheme.spacing.m))
            Column(horizontalAlignment = Alignment.End) {
                StatusBadge(
                    text = if (exists) "文件在" else "已失效",
                    container = if (exists) colors.successContainer else colors.neutralContainer,
                    content = if (exists) colors.onSuccessContainer else colors.onNeutralContainer
                )
                Spacer(Modifier.height(MpkgTheme.spacing.s))
                AppButton("定位", { onReveal(entry) }, kind = ButtonKind.GHOST, enabled = exists)
            }
        }
    }
}

@Composable
private fun Thumb(bitmap: ImageBitmap?, kind: String) {
    val colors = MpkgTheme.colors
    val shape = RoundedCornerShape(MpkgTheme.radius.icon)
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
            Icon(
                if (kind == "video") AppIcons.Video else AppIcons.Image,
                contentDescription = null,
                tint = colors.borderStrong,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String =
    if (timestamp <= 0L) "时间未知" else timeFormat.format(Date(timestamp))

/** 供界面复用：把输出路径显示成「父目录 / 文件名」。 */
fun shortenPath(path: String): String {
    val file = File(path)
    val parent = file.parentFile?.name ?: return path
    return "$parent / ${file.name}"
}
