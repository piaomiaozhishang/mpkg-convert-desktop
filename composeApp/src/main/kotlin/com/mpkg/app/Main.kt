package com.mpkg.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mpkg.app.platform.DesktopIntegration
import com.mpkg.app.settings.WindowState
import com.mpkg.app.tools.CliMain
import com.mpkg.app.ui.MpkgApp
import com.mpkg.app.viewmodel.AppViewModel
import com.mpkg.app.viewmodel.ConvertViewModel
import com.mpkg.app.viewmodel.appScope
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * 程序入口。
 *
 * 三种启动方式共用一个 mainClass：
 * 1. 无参数 → 打开图形界面；
 * 2. 参数全是已存在的 `.mpkg` / `.pkg` 文件 → 图形界面并把它们直接入队
 *    （文件关联/「打开方式」走的就是这条路，T4.1 验收项）；
 * 3. 其它参数 → 命令行模式（T1.3 的端到端验证通道），
 *    例如 `--args="sample/texture.tex out.png"`；也可用 `--cli` 强制进入。
 */
fun main(args: Array<String>) {
    if (isGraphicalLaunch(args)) {
        launchGui(args.map { java.io.File(it) })
    } else {
        exitProcess(CliMain.run(args))
    }
}

/** 参数全是可识别的包文件时按「双击打开」处理，否则按命令行处理。 */
private fun isGraphicalLaunch(args: Array<String>): Boolean {
    if (args.isEmpty()) return true
    if (args.any { it.startsWith("-") }) return false
    return args.all { path ->
        val name = path.lowercase()
        (name.endsWith(".mpkg") || name.endsWith(".pkg")) && java.io.File(path).isFile
    }
}

private fun launchGui(prefill: List<java.io.File>) {
    application {
        val scope = remember { appScope() }
        val app = remember { AppViewModel(scope) }
        val settings by app.settings.collectAsState()

        val initial = settings.window
        val windowState = rememberWindowState(
            size = DpSize(initial.width.dp, initial.height.dp),
            position = if (initial.x == WindowState.UNSET || initial.y == WindowState.UNSET) {
                WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
            } else {
                WindowPosition(initial.x.dp, initial.y.dp)
            }
        )

        val convert = remember(app) {
            ConvertViewModel(scope, app) { file -> DesktopIntegration.openFile(file) }
        }

        // 双击关联文件启动：直接入队，不需要用户再拖一次。
        LaunchedEffect(Unit) {
            if (prefill.isNotEmpty()) convert.addFiles(prefill)
        }

        // 尺寸/位置记忆（T2.1 验收项）。拖动会高频触发，交给 ViewModel 去抖落盘。
        LaunchedEffect(windowState.size, windowState.position, windowState.placement) {
            if (windowState.placement == WindowPlacement.Floating) {
                app.updateWindowState {
                    it.copy(
                        width = windowState.size.width.value.toInt().coerceAtLeast(WindowState.MIN_WIDTH),
                        height = windowState.size.height.value.toInt().coerceAtLeast(WindowState.MIN_HEIGHT),
                        x = windowState.position.x.value.toInt(),
                        y = windowState.position.y.value.toInt(),
                        maximized = false
                    )
                }
            } else if (windowState.placement == WindowPlacement.Maximized) {
                delay(300)
                app.updateWindowState { it.copy(maximized = true) }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "${AppInfo.APP_NAME} v${AppInfo.VERSION}"
        ) {
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(WindowState.MIN_WIDTH, WindowState.MIN_HEIGHT)
                // 应用图标（T4.1 产出物）；缺失时保持系统默认图标。
                val icons = DesktopIntegration.loadAppIconSet()
                if (icons.isNotEmpty()) window.iconImages = icons
            }
            MpkgApp(app = app, convert = convert)
        }
    }
}
