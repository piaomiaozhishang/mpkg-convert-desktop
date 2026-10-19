package com.mpkg.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mpkg.app.queue.ConversionTask
import com.mpkg.app.settings.AppSettings
import com.mpkg.app.ui.components.AppButton
import com.mpkg.app.ui.components.AppIcons
import com.mpkg.app.ui.components.ButtonKind
import com.mpkg.app.ui.components.DropZone
import com.mpkg.app.ui.components.EmptyState
import com.mpkg.app.ui.components.HairlineDivider
import com.mpkg.app.ui.components.PageHeader
import com.mpkg.app.ui.components.SupportedFormatsHint
import com.mpkg.app.ui.components.TaskCard
import com.mpkg.app.ui.theme.Motion
import com.mpkg.app.ui.theme.MpkgTheme

/** 转换主界面（任务卡 T2.2 / T2.3 / T2.4 / T2.5 的可视部分）。 */
@Composable
fun ConvertScreen(
    tasks: List<ConversionTask>,
    dragActive: Boolean,
    settings: AppSettings,
    reduceMotion: Boolean,
    estimateSeconds: Int,
    waitingCount: Int,
    runningCount: Int,
    doneCount: Int,
    failedCount: Int,
    selectedId: String?,
    onPick: () -> Unit,
    onChangeOutputDir: () -> Unit,
    onToggleOpenWhenDone: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
    onTaskSelect: (String) -> Unit,
    onTaskCancel: (String) -> Unit,
    onTaskRemove: (String) -> Unit,
    onTaskRetry: (String) -> Unit,
    onTaskOpen: (ConversionTask) -> Unit,
    onTaskReveal: (ConversionTask) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = MpkgTheme.spacing.xl)) {
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            PageHeader("转换", "拖入 .mpkg / .pkg，提取原图、MP4，或合成场景壁纸")
            Spacer(Modifier.height(MpkgTheme.spacing.l))
            DropZone(
                active = dragActive,
                enabled = true,
                reduceMotion = reduceMotion,
                onPick = onPick
            )
            Spacer(Modifier.height(MpkgTheme.spacing.s))
            SupportedFormatsHint()
            Spacer(Modifier.height(MpkgTheme.spacing.l))

            if (tasks.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Add,
                    title = "队列还是空的",
                    description = "把 .mpkg / .pkg 拖到上面的虚线框，或点击选择文件。\n" +
                        "支持一次拖入多个文件批量转换，解码期间界面不会卡住。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MpkgTheme.spacing.s),
                    contentPadding = PaddingValues(bottom = MpkgTheme.spacing.l)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            reduceMotion = reduceMotion,
                            selected = task.id == selectedId,
                            onSelect = { onTaskSelect(task.id) },
                            onCancel = { onTaskCancel(task.id) },
                            onRemove = { onTaskRemove(task.id) },
                            onRetry = { onTaskRetry(task.id) },
                            onOpen = { onTaskOpen(task) },
                            onReveal = { onTaskReveal(task) }
                        )
                    }
                }
            }
        }

        BottomBar(
            outputDir = settings.outputDir,
            openWhenDone = settings.openOutputWhenDone,
            reduceMotion = reduceMotion,
            estimateSeconds = estimateSeconds,
            waitingCount = waitingCount,
            runningCount = runningCount,
            doneCount = doneCount,
            failedCount = failedCount,
            onChangeOutputDir = onChangeOutputDir,
            onToggleOpenWhenDone = onToggleOpenWhenDone,
            onStart = onStart,
            onCancelAll = onCancelAll,
            onClearFinished = onClearFinished
        )
    }
}

@Composable
private fun BottomBar(
    outputDir: String,
    openWhenDone: Boolean,
    reduceMotion: Boolean,
    estimateSeconds: Int,
    waitingCount: Int,
    runningCount: Int,
    doneCount: Int,
    failedCount: Int,
    onChangeOutputDir: () -> Unit,
    onToggleOpenWhenDone: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit
) {
    val colors = MpkgTheme.colors
    val bottomPadding by animateDpAsState(
        targetValue = MpkgTheme.spacing.l,
        animationSpec = Motion.enter(reduceMotion),
        label = "bottomBarPadding"
    )
    Column(Modifier.background(colors.background)) {
        HairlineDivider()
        Column(Modifier.padding(horizontal = MpkgTheme.spacing.xl, vertical = bottomPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Folder,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(MpkgTheme.spacing.s))
                Text(
                    "输出目录：$outputDir",
                    color = colors.textSecondary,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(MpkgTheme.spacing.s))
                Text(
                    "更改",
                    color = colors.accent,
                    fontSize = MpkgTheme.fontSizes.tiny,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(horizontal = MpkgTheme.spacing.xs)
                        .clickable(onClick = onChangeOutputDir)
                )
            }
            Spacer(Modifier.height(MpkgTheme.spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = openWhenDone,
                    onCheckedChange = onToggleOpenWhenDone,
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        uncheckedColor = colors.borderStrong,
                        checkmarkColor = colors.onAccent
                    ),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(MpkgTheme.spacing.xs))
                Text(
                    "完成后打开输出目录",
                    color = colors.textSecondary,
                    fontSize = MpkgTheme.fontSizes.tiny
                )
            }
            Spacer(Modifier.height(MpkgTheme.spacing.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summaryText(waitingCount, runningCount, doneCount, failedCount),
                        color = colors.textSecondary,
                        fontSize = MpkgTheme.fontSizes.tiny
                    )
                    if (estimateSeconds > 0) {
                        Text(
                            "预计约 $estimateSeconds 秒",
                            color = colors.textTertiary,
                            fontSize = MpkgTheme.fontSizes.tiny
                        )
                    }
                }
                if (doneCount + failedCount > 0 && runningCount == 0) {
                    AppButton("清理已完成", onClearFinished, kind = ButtonKind.GHOST)
                    Spacer(Modifier.width(MpkgTheme.spacing.s))
                }
                if (runningCount > 0) {
                    AppButton("全部取消", onCancelAll, kind = ButtonKind.SECONDARY, icon = AppIcons.Stop)
                    Spacer(Modifier.width(MpkgTheme.spacing.s))
                }
                AppButton(
                    text = if (runningCount > 0) "转换中…" else "开始转换",
                    onClick = onStart,
                    kind = ButtonKind.PRIMARY,
                    enabled = waitingCount > 0,
                    minWidth = 128
                )
            }
        }
    }
}

private fun summaryText(waiting: Int, running: Int, done: Int, failed: Int): String {
    val parts = ArrayList<String>(4)
    if (waiting > 0) parts.add("$waiting 个等待")
    if (running > 0) parts.add("$running 个进行中")
    if (done > 0) parts.add("$done 个已完成")
    if (failed > 0) parts.add("$failed 个失败")
    return if (parts.isEmpty()) "还没有任务" else parts.joinToString(" · ")
}
