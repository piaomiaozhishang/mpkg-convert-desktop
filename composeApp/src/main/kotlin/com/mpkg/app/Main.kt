package com.mpkg.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mpkg.app.tools.CliMain
import kotlin.system.exitProcess

/**
 * 程序入口。
 *
 * 带参数时进入命令行模式（任务卡 T1.3 的端到端验证通道），不带参数时打开图形界面。
 * 这样 `./gradlew :composeApp:run --args="sample/texture.tex out.png"` 与
 * 直接双击启动两种用法共用同一个 mainClass。
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        exitProcess(CliMain.run(args))
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "mpkg 壁纸提取器"
        ) {
            MaterialTheme {
                Surface {
                    Text("mpkg 壁纸提取器")
                }
            }
        }
    }
}
