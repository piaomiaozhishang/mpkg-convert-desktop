package com.mpkg.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
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
import com.mpkg.app.update.UpdateInfo
import com.mpkg.app.util.AppLog
import com.mpkg.app.viewmodel.AppViewModel
import com.mpkg.app.viewmodel.ConvertViewModel
import com.mpkg.app.viewmodel.Screen
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * 应用根界面（任务卡 T2.1）。
 *
 * 单窗口 + 左侧导航 + 页面切换；导航/设置/历史都来自 [AppViewModel] 这一个数据源。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val updateInfo by app.updateAvailable.collectAsState()

    var confirmClearHistory by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 窗口级别的最小尺寸（报告 T2.1 验收：最小 960×600）
    LaunchedEffect(window) {
        window.minimumSize = Dimension(
            com.mpkg.app.settings.WindowState.MIN_WIDTH,
            com.mpkg.app.settings.WindowState.MIN_HEIGHT
        )
    }

    // 窗口级拖拽（T2.2）：
    // Compose Desktop 1.7 的场景架构会在覆盖整个客户区的根容器上安装自己的 AWT
    // DropTarget，AWT 只会把拖放派发给最深的持靶组件，所以挂在 window 上的
    // WindowDropTarget 收不到客户区事件——主通道必须走 Compose 的
    // dragAndDropTarget（挂在根 Box 上，全窗口可拖）。
    // 窗口级 AWT target 仍然保留：兜住标题栏等非客户区；两条通道都汇入
    // convert.handleDrop，里面有按文件列表的 900ms 去重，不会重复入队。
    LaunchedEffect(window) {
        WindowDropTarget(
            component = window,
            onDragActive = convert::setDragActive,
            onFilesDropped = convert::handleDrop
        ).install()
    }

    val fileDropTarget = remember(convert) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) = convert.setDragActive(true)
            override fun onEntered(event: DragAndDropEvent) = convert.setDragActive(true)
            override fun onMoved(event: DragAndDropEvent) = Unit
            override fun onExited(event: DragAndDropEvent) = convert.setDragActive(false)
            override fun onEnded(event: DragAndDropEvent) = convert.setDragActive(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                convert.setDragActive(false)
                val files = event.readDroppedFiles()
                if (files.isEmpty()) {
                    AppLog.w("拖入的内容里没有可用文件")
                    return false
                }
                AppLog.i("拖入 ${files.size} 个文件: ${files.joinToString { it.name }}")
                convert.handleDrop(files)
                return true
            }
        }
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

    fun openProjectUrl() {
        val url = com.mpkg.app.AppInfo.PROJECT_URL
        if (!DesktopIntegration.openUrl(url)) app.showToast("无法打开浏览器，请手动访问：$url")
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
                // 全窗口接收文件拖放（客户区任意位置松开即入队）
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event -> event.isFileDrag() },
                    target = fileDropTarget
                )
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
                            },
                            onCheckUpdate = { app.checkForUpdate(manual = true) },
                            onOpenProjectUrl = ::openProjectUrl
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
                            },
                            onOpenProjectUrl = ::openProjectUrl,
                            onCheckUpdate = { app.checkForUpdate(manual = true) }
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

        // 更新公告：Gitee 上的版本高于本地时弹出（强制更新时不可忽略、不可关闭）
        updateInfo?.let { info ->
            UpdateAnnouncementDialog(
                info = info,
                currentVersion = com.mpkg.app.AppInfo.VERSION,
                onDownload = app::downloadUpdate,
                onIgnore = app::ignoreUpdate,
                onDismiss = app::dismissUpdate
            )
        }
    }
}

private const val TOAST_DURATION_MS = 3000L

/**
 * 桌面端拖拽事件里的 nativeEvent 就是 AWT 的 `DropTargetDragEvent` /
 * `DropTargetDropEvent`（Compose 1.7 的 AwtDragAndDropManager 原样透传），
 * 因此文件列表仍走 `DataFlavor.javaFileListFlavor` 读取。
 *
 * nativeEvent 属于 ExperimentalComposeUiApi，这里显式 OptIn。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.isFileDrag(): Boolean {
    val awtEvent = nativeEvent as? DropTargetDragEvent ?: return false
    return awtEvent.transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true
}

@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.readDroppedFiles(): List<File> {
    val awtEvent = nativeEvent as? DropTargetDropEvent ?: return emptyList()
    val transferable = awtEvent.transferable ?: return emptyList()
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val data = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
    return data.filterIsInstance<File>().filter { it.isFile }
}

/**
 * 更新公告弹窗。
 *
 * 「下载新版本」打开下载地址（update.json 的 downloadUrl → 设置里的下载地址 → 项目主页）；
 * 「忽略新版本（再也不提示）」把版本号写进设置，后续检查不再弹。
 * update.json 里发 `"force": true` 时进入强制更新：忽略钮隐藏、点窗外也关不掉。
 */
@Composable
private fun UpdateAnnouncementDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDownload: (UpdateInfo) -> Unit,
    onIgnore: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!info.force) onDismiss() },
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 v$currentVersion，最新版本 v${info.version}。")
                if (!info.notes.isNullOrBlank()) {
                    Text(info.notes)
                }
                Text(
                    if (info.force) {
                        "这是一个必须安装的更新：更新前将无法继续使用。"
                    } else {
                        "下载页将在浏览器中打开；也可以之后在「设置 → 更新」里手动检查。"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(info) }) { Text("下载新版本") }
        },
        dismissButton = if (!info.force) {
            {
                TextButton(onClick = { onIgnore(info) }) { Text("忽略新版本（再也不提示）") }
            }
        } else {
            null
        }
    )
}
