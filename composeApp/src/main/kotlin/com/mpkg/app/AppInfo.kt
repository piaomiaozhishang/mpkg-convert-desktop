package com.mpkg.app

/**
 * 应用元信息（任务卡 T4.2）。
 *
 * 「许可与出处」里的大段第三方出处声明已按用户要求全部移除（内容有误，不再展示）；
 * MIT 许可本身保留在关于页与安装程序许可文件里。
 */
object AppInfo {

    const val APP_NAME = "mpkg 壁纸提取器"
    const val PACKAGE_NAME = "mpkg-tool"
    const val VERSION = "1.0.1"
    const val LICENSE = "MIT"

    /** 项目主页（关于页 / 设置页展示，点击用系统浏览器打开）。 */
    const val PROJECT_URL = "https://github.com/piaomiaozhishang/mpkg-convert-desktop"

    const val FEEDBACK_URL = "https://b23.tv/JWQpitx"

    /** core 模块复用自已上线的 Android 版，UI 为全新桌面实现。 */
    const val DESCRIPTION = "解析 .mpkg / .pkg 壁纸包，提取原图与 MP4，合成静态场景壁纸。"

    /** 已知边界，README 与「关于」页保持一致（照实说）。 */
    val KNOWN_LIMITATIONS: List<String> = listOf(
        "场景合成输出 PNG：支持图片图层、尺寸与位置、透明度、颜色和 Opacity 遮罩；尚未实现动态场景转 MP4",
        "水波、呼吸抖动、光束、脉冲、粒子、音频响应及脚本等未还原效果，会列在任务的完整合成说明中",
        "纯代码 / 网页类壁纸、未导出的原始场景工程：包内没有现成 MP4 或纹理，无法提取",
        "TEXF 动帧容器（动态序列帧壁纸）暂不支持，遇到时跳过并提示",
        "ASTC / BC7（纹理 Format 12）可识别但不支持解码",
        "加密或非标准魔数的包按格式错误处理",
        "GPU 解码尚未实现，设置页中的开关仅占位；CPU 解码 4K 单张约 1–3 秒",
        "未压缩的视频壁纸（.tex 内直接放 MP4）走流式复制，几百 MB 也不占内存；" +
            "但 LZ4 压缩过的视频纹理必须先整体解压，超大文件仍会占用较多内存"
    )
}
