package com.mpkg.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.WindowScope
import com.mpkg.app.history.HistoryEntry
import com.mpkg.app.platform.DesktopIntegration
import com.mpkg.app.platform.FileDialogs
import com.mpkg.app.platform.PlatformPaths
import com.mpkg.app.platform.WindowDropTarget
import com.mpkg.app.queue.TaskStatus
import com.mpkg.app.ui.components.NavRail
import com.mpkg.app.ui.components.VerticalHairline
import com.mpkg.app.ui.screens.AboutScreen
import com.mpkg.app.ui.screens.ConvertScreen
import com.mpkg.app.ui.screens.HistoryScreen
import com.mpkg.app.ui.screens.SettingsScreen
import com.mpkg.app.ui.theme.MpkgTheme
import com.mpkg.app.ui.theme.ThemeMode
import com.mpkg.app.viewmodel.AppViewModel
import com.mpkg.app.viewmodel.ConvertViewModel
import com.mpkg.app.viewmodel.Screen
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * 应用根界面（任务卡 T2.1）。
 *
 * 单窗口 + 左侧导航 + 页面切换；导航/设置/历史都来自 [AppViewModel] 这一个数据源。
 */
@Composable
fun WindowScope.MpkgApp(app: AppViewModel, convert: ConvertViewModel) {
    val settings by app.settings.collectAsState()
    val screen by app.screen.collectAsState()
    val tasks by convert.tasks.collectAsState()
    val dragActive by convert.dragActive.collectAsState()
    val toast by app.toast.collectAsState()
    val history by app.history.collectAsState()
    val thumbnails by app.thumbnails.collectAsState()
    val selectedId by convert.selectedId.collectAsState()

    var confirmClearHistory by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 窗口级别的最小尺寸（报告 T2.1 验收：最小 960×600）
    LaunchedEffect(window) {
        window.minimumSize = Dimension(
            com.mpkg.app.settings.WindowState.MIN_WIDTH,
            com.mpkg.app.settings.WindowState.MIN_HEIGHT
        )
    }

    // 窗口级拖拽：AWT DropTarget 是主通道，Compose 侧只做高亮反馈。
    LaunchedEffect(window) {
        WindowDropTarget(
            component = window,
            onDragActive = convert::setDragActive,
            onFilesDropped = convert::handleDrop
        ).install()
    }

    fun pickFiles() {
        val files = FileDialogs.choosePackageFiles(window)
        if (files.isNotEmpty()) convert.addFiles(files)
    }

    fun openFile(file: File) {
        if (!DesktopIntegration.openFile(file)) app.showToast("无法打开该文件，请手动查看输出目录")
    }

    fun revealFile(file: File) {
        if (!DesktopIntegration.revealInFileManager(file)) app.showToast("无法打开文件所在位置")
    }

    fun toggleTheme() {
        app.updateSettings { current ->
            current.copy(
                themeMode = if (current.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
            )
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.isCtrlPressed && event.key == Key.O -> {
                pickFiles(); true
            }
            event.isCtrlPressed && event.key == Key.Comma -> {
                app.navigate(Screen.SETTINGS); true
            }
            event.key == Key.Escape -> {
                if (convert.runningCount() > 0) {
                    convert.cancelAll()
                    app.showToast("已取消全部进行中的任务")
                } else if (screen != Screen.CONVERT) {
                    app.navigate(Screen.CONVERT)
                }
                true
            }
            event.key == Key.Delete -> {
                val task = tasks.firstOrNull { it.id == selectedId }
                if (task != null && task.removable) {
                    convert.remove(task.id)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    // 全部完成后按设置打开输出目录
    LaunchedEffect(tasks.map { it.status }) {
        if (settings.openOutputWhenDone && tasks.isNotEmpty() &&
            tasks.all { it.status != TaskStatus.WAITING && it.status != TaskStatus.RUNNING } &&
            tasks.any { it.status == TaskStatus.DONE }
        ) {
            DesktopIntegration.openDirectory(File(settings.outputDir))
        }
    }

    MpkgTheme(mode = settings.themeMode, accent = settings.accent) {
        val colors = MpkgTheme.colors
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent(::handleKey)
        ) {
            Row(Modifier.fillMaxSize()) {
                NavRail(
                    current = screen,
                    onNavigate = app::navigate,
                    isDark = colors.isDark,
                    onToggleTheme = ::toggleTheme,
                    reduceMotion = settings.reduceMotion
                )
                // 注意：这里只能用 fillMaxHeight()。若写成 fillMaxSize().width(...)，
                // 传入约束会被固定成父级最大宽度，竖线会撑满整行、把主内容区挤出窗口。
                VerticalHairline(Modifier.fillMaxHeight().width(MpkgTheme.strokes.hairline))
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (screen) {
                        Screen.CONVERT -> ConvertScreen(
                            tasks = tasks,
                            dragActive = dragActive,
                            settings = settings,
                            reduceMotion = settings.reduceMotion,
                            estimateSeconds = convert.estimateSeconds(),
                            waitingCount = tasks.count { it.status == TaskStatus.WAITING },
                            runningCount = tasks.count { it.status == TaskStatus.RUNNING },
                            doneCount = tasks.count { it.status == TaskStatus.DONE },
                            failedCount = tasks.count { it.status == TaskStatus.FAILED },
                            selectedId = selectedId,
                            onPick = ::pickFiles,
                            onChangeOutputDir = {
                                FileDialogs.chooseOutputDir(window, File(settings.outputDir))?.let { dir ->
                                    app.updateSettings { it.copy(outputDir = dir.absolutePath) }
                                    app.showToast("输出目录已更改为 ${dir.absolutePath}")
                                }
                            },
                            onToggleOpenWhenDone = { value ->
                                app.updateSettings { it.copy(openOutputWhenDone = value) }
                            },
                            onStart = convert::start,
                            onCancelAll = convert::cancelAll,
                            onClearFinished = convert::clearFinished,
                            onTaskSelect = convert::select,
                            onTaskCancel = convert::cancel,
                            onTaskRemove = convert::remove,
                            onTaskRetry = convert::retry,
                            onTaskOpen = { task -> task.outputs.firstOrNull()?.let { openFile(File(it.path)) } },
                            onTaskReveal = { task -> task.outputs.firstOrNull()?.let { revealFile(File(it.path)) } }
                        )

                        Screen.HISTORY -> HistoryScreen(
                            entries = history,
                            thumbnails = thumbnails,
                            onOpen = { entry: HistoryEntry ->
                                app.openHistoryEntry(entry, ::openFile)
                                app.touchThumbnail(entry.id)
                            },
                            onReveal = { entry -> entry.outputs.firstOrNull()?.let { revealFile(File(it)) } },
                            onClear = { confirmClearHistory = true }
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onChange = { transform -> app.updateSettings(transform = transform) },
                            onChooseOutputDir = {
                                FileDialogs.chooseOutputDir(window, File(settings.outputDir))?.let { dir ->
                                    app.updateSettings { it.copy(outputDir = dir.absolutePath) }
                                }
                            }
                        )

                        Screen.ABOUT -> AboutScreen(
                            onCopyDiagnostics = {
                                val text = buildString {
                                    appendLine("${com.mpkg.app.AppInfo.APP_NAME} v${com.mpkg.app.AppInfo.VERSION}")
                                    appendLine(PlatformPaths.describe())
                                    appendLine("配置目录：${PlatformPaths.configDir()}")
                                    appendLine("主题=${settings.themeMode} 主色=${settings.accent} 并发=${settings.concurrency}")
                                    appendLine("输出目录=${settings.outputDir}")
                                    appendLine("覆盖策略=${settings.overwritePolicy} 解码=${settings.decodeMode}")
                                }
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(text), null)
                                app.showToast("诊断信息已复制到剪贴板")
                            }
                        )
                    }
                }
            }

            // 轻提示：完成/失败/复制反馈，3 秒后自动消失
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = MpkgTheme.spacing.xl)) {
                AnimatedVisibility(
                    visible = toast != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val shape = RoundedCornerShape(MpkgTheme.radius.button)
                    Text(
                        text = toast.orEmpty(),
                        color = colors.textPrimary,
                        fontSize = MpkgTheme.fontSizes.tiny,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surface)
                            .border(MpkgTheme.strokes.hairline, colors.borderStrong, shape)
                            .padding(horizontal = MpkgTheme.spacing.l, vertical = MpkgTheme.spacing.s)
                    )
                }
            }
            LaunchedEffect(toast) {
                if (toast != null) {
                    kotlinx.coroutines.delay(TOAST_DURATION_MS)
                    app.consumeToast()
                }
            }
        }

        if (confirmClearHistory) {
            AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { Text("清空历史记录？") },
                text = { Text("只删除记录与缩略图缓存，不会动你已经导出的文件。") },
                confirmButton = {
                    TextButton(onClick = {
                        app.clearHistory()
                        confirmClearHistory = false
                    }) { Text("清空") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearHistory = false }) { Text("取消") }
                }
            )
        }
    }
}

private const val TOAST_DURATION_MS = 3000L
