package com.mpkg.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 应用内置矢量图标。
 *
 * 全部自带实现，不依赖 material-icons 扩展包：一是控制安装包体积（T4.1 要求
 * Windows 包 ≤ 80 MB），二是保证三平台渲染一致。颜色统一由调用方通过
 * `Icon(tint = ...)` 注入，因此这里只画形状、不写死颜色。
 */
object AppIcons {
    val GitHub: ImageVector by lazy {
        icon("GitHub") {
            addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString("M12 .297C5.37 .297 0 5.67 0 12.297c0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.043-1.61-4.043-1.61-.546-1.387-1.333-1.756-1.333-1.756-1.087-.744.083-.729.083-.729 1.205.084 1.838 1.237 1.838 1.237 1.07 1.834 2.807 1.304 3.492.997.108-.775.418-1.305.762-1.605-2.665-.305-5.467-1.334-5.467-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23a11.5 11.5 0 0 1 3-.405c1.02.005 2.045.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.62-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12z").toNodes(), fill = SolidColor(Color.Black))
        }
    }


    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            fill = null,
            pathBuilder = block
        )
    }

    /** 转换：向下箭头 + 托盘。 */
    val Convert: ImageVector by lazy {
        icon("Convert") {
            stroke {
                moveTo(12f, 3.5f); verticalLineTo(13.5f)
                moveTo(7.8f, 9.6f); lineTo(12f, 13.8f); lineTo(16.2f, 9.6f)
                moveTo(4.5f, 15.5f); verticalLineTo(18.5f)
                horizontalLineTo(19.5f); verticalLineTo(15.5f)
            }
        }
    }

    /** 历史记录：时钟。 */
    val History: ImageVector by lazy {
        icon("History") {
            stroke {
                moveTo(12f, 3.8f)
                curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12f)
                curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12f, 20.2f)
                curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12f)
                curveTo(3.8f, 9.2f, 5.2f, 6.8f, 7.3f, 5.3f)
                moveTo(12f, 7.4f); verticalLineTo(12.2f); lineTo(15.3f, 14.1f)
            }
        }
    }

    /** 设置：齿轮。 */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke {
                moveTo(12f, 8.6f)
                curveTo(13.9f, 8.6f, 15.4f, 10.1f, 15.4f, 12f)
                curveTo(15.4f, 13.9f, 13.9f, 15.4f, 12f, 15.4f)
                curveTo(10.1f, 15.4f, 8.6f, 13.9f, 8.6f, 12f)
                curveTo(8.6f, 10.1f, 10.1f, 8.6f, 12f, 8.6f)
                close()
                moveTo(12f, 3.4f); verticalLineTo(5.6f)
                moveTo(12f, 18.4f); verticalLineTo(20.6f)
                moveTo(3.4f, 12f); horizontalLineTo(5.6f)
                moveTo(18.4f, 12f); horizontalLineTo(20.6f)
                moveTo(5.9f, 5.9f); lineTo(7.5f, 7.5f)
                moveTo(16.5f, 16.5f); lineTo(18.1f, 18.1f)
                moveTo(18.1f, 5.9f); lineTo(16.5f, 7.5f)
                moveTo(7.5f, 16.5f); lineTo(5.9f, 18.1f)
            }
        }
    }

    /** 关于：信息。 */
    val Info: ImageVector by lazy {
        icon("Info") {
            stroke {
                moveTo(12f, 3.8f)
                curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12f)
                curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12f, 20.2f)
                curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12f)
                curveTo(3.8f, 7.5f, 7.5f, 3.8f, 12f, 3.8f)
                close()
                moveTo(12f, 11f); verticalLineTo(16.4f)
                moveTo(12f, 7.6f); verticalLineTo(7.8f)
            }
        }
    }

    val Sun: ImageVector by lazy {
        icon("Sun") {
            stroke {
                moveTo(12f, 8.4f)
                curveTo(14f, 8.4f, 15.6f, 10f, 15.6f, 12f)
                curveTo(15.6f, 14f, 14f, 15.6f, 12f, 15.6f)
                curveTo(10f, 15.6f, 8.4f, 14f, 8.4f, 12f)
                curveTo(8.4f, 10f, 10f, 8.4f, 12f, 8.4f)
                close()
                moveTo(12f, 3.6f); verticalLineTo(5.2f)
                moveTo(12f, 18.8f); verticalLineTo(20.4f)
                moveTo(3.6f, 12f); horizontalLineTo(5.2f)
                moveTo(18.8f, 12f); horizontalLineTo(20.4f)
                moveTo(6.1f, 6.1f); lineTo(7.2f, 7.2f)
                moveTo(16.8f, 16.8f); lineTo(17.9f, 17.9f)
                moveTo(17.9f, 6.1f); lineTo(16.8f, 7.2f)
                moveTo(7.2f, 16.8f); lineTo(6.1f, 17.9f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        icon("Moon") {
            stroke {
                moveTo(20f, 14.4f)
                curveTo(18.7f, 15.5f, 17f, 16.2f, 15.1f, 16.2f)
                curveTo(11f, 16.2f, 7.7f, 12.9f, 7.7f, 8.8f)
                curveTo(7.7f, 7.2f, 8.2f, 5.7f, 9f, 4.4f)
                curveTo(5.6f, 5.6f, 3.2f, 8.9f, 3.2f, 12.7f)
                curveTo(3.2f, 17.6f, 7.1f, 21.5f, 12f, 21.5f)
                curveTo(15.6f, 21.5f, 18.6f, 19.4f, 20f, 14.4f)
                close()
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                moveTo(3.6f, 7.4f)
                curveTo(3.6f, 6.6f, 4.2f, 6f, 5f, 6f)
                horizontalLineTo(9.4f)
                lineTo(11.2f, 8.2f)
                horizontalLineTo(19f)
                curveTo(19.8f, 8.2f, 20.4f, 8.8f, 20.4f, 9.6f)
                verticalLineTo(17.4f)
                curveTo(20.4f, 18.2f, 19.8f, 18.8f, 19f, 18.8f)
                horizontalLineTo(5f)
                curveTo(4.2f, 18.8f, 3.6f, 18.2f, 3.6f, 17.4f)
                close()
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                moveTo(6.4f, 6.4f); lineTo(17.6f, 17.6f)
                moveTo(17.6f, 6.4f); lineTo(6.4f, 17.6f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            stroke {
                moveTo(5.4f, 12.6f); lineTo(10f, 17.2f); lineTo(18.6f, 7.4f)
            }
        }
    }

    val Warning: ImageVector by lazy {
        icon("Warning") {
            stroke {
                moveTo(12f, 4.2f); lineTo(21f, 19.4f); lineTo(3f, 19.4f); close()
                moveTo(12f, 9.6f); verticalLineTo(14.4f)
                moveTo(12f, 16.8f); verticalLineTo(17f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            stroke {
                moveTo(19.4f, 12f)
                curveTo(19.4f, 16.1f, 16.1f, 19.4f, 12f, 19.4f)
                curveTo(7.9f, 19.4f, 4.6f, 16.1f, 4.6f, 12f)
                curveTo(4.6f, 7.9f, 7.9f, 4.6f, 12f, 4.6f)
                curveTo(14.4f, 4.6f, 16.6f, 5.8f, 17.9f, 7.6f)
                moveTo(18.6f, 3.9f); verticalLineTo(8.1f); horizontalLineTo(14.4f)
            }
        }
    }

    val Image: ImageVector by lazy {
        icon("Image") {
            stroke {
                moveTo(4.2f, 5.4f)
                horizontalLineTo(19.8f); verticalLineTo(18.6f); horizontalLineTo(4.2f); close()
                moveTo(4.2f, 15.2f); lineTo(9.2f, 10.4f); lineTo(13.4f, 14.4f)
                lineTo(16.2f, 11.8f); lineTo(19.8f, 15.2f)
                moveTo(15.6f, 9.2f); verticalLineTo(9.4f)
            }
        }
    }

    val Video: ImageVector by lazy {
        icon("Video") {
            stroke {
                moveTo(3.8f, 6.2f)
                horizontalLineTo(20.2f); verticalLineTo(17.8f); horizontalLineTo(3.8f); close()
                moveTo(10.2f, 9.6f); lineTo(15.2f, 12f); lineTo(10.2f, 14.4f); close()
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroke {
                moveTo(9f, 9f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(9f); close()
                moveTo(15f, 5f); horizontalLineTo(5f); verticalLineTo(15f)
            }
        }
    }

    val ExternalOpen: ImageVector by lazy {
        icon("ExternalOpen") {
            stroke {
                moveTo(13.4f, 5f); horizontalLineTo(19f); verticalLineTo(10.6f)
                moveTo(19f, 5f); lineTo(11.2f, 12.8f)
                moveTo(17.2f, 14.2f); verticalLineTo(19f); horizontalLineTo(5f); verticalLineTo(6.8f)
                horizontalLineTo(9.8f)
            }
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            stroke {
                moveTo(7.4f, 7.4f); horizontalLineTo(16.6f); verticalLineTo(16.6f)
                horizontalLineTo(7.4f); close()
            }
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            stroke {
                moveTo(12f, 5.6f); verticalLineTo(18.4f)
                moveTo(5.6f, 12f); horizontalLineTo(18.4f)
            }
        }
    }
}
