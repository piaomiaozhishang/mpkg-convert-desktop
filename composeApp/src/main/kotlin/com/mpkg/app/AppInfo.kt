package com.mpkg.app

/** 一处第三方出处或许可（T4.2 要求原样保留，不得删除或改写）。 */
data class Attribution(
    val name: String,
    val url: String,
    val license: String,
    val usage: String
)

/**
 * 应用元信息与出处声明（任务卡 T4.2）。
 *
 * 「许可与出处」一节的内容必须与交接包 README 保持一致：引用代码的 MIT 声明原样保留。
 */
object AppInfo {

    const val APP_NAME = "mpkg 壁纸提取器"
    const val PACKAGE_NAME = "mpkg-tool"
    const val VERSION = "1.0.0"
    const val LICENSE = "MIT"
    const val PROJECT_URL = "https://github.com/piaomiaozhishang/mpkg-convert"

    /** core 模块复用自已上线的 Android 版，UI 为全新桌面实现。 */
    const val DESCRIPTION = "解析 Wallpaper Engine 导出的 .mpkg / .pkg 壁纸包，提取 4K 原图与 MP4。"

    val ATTRIBUTIONS: List<Attribution> = listOf(
        Attribution(
            name = "RePKG",
            url = "https://github.com/notscuffed/repkg",
            license = "MIT",
            usage = "kotlin-core/Dxt.kt 由其实 S3TC 解压实现移植（源自 LibSquish）"
        ),
        Attribution(
            name = "社区 dsh-mpkg-wallpaper 插件",
            url = "https://github.com/piaomiaozhishang/mpkg-convert",
            license = "MIT",
            usage = "kotlin-core/ImageRanker.kt 的主图打分启发式由其移植"
        ),
        Attribution(
            name = "Khronos OpenGL ES 数据格式规范 / Android DEQP 一致性测试",
            url = "https://www.khronos.org/registry/OpenGL/index_es.php",
            license = "规范性引用",
            usage = "ETC1/ETC2/EAC 位级解码的交叉验证依据"
        ),
        Attribution(
            name = "yakisoba0728/Waple",
            url = "https://github.com/yakisoba0728/Waple",
            license = "MIT",
            usage = "格式映射关系的交叉核对"
        ),
        Attribution(
            name = "Pillow (Python Imaging Library)",
            url = "https://python-pillow.org/",
            license = "MIT-CMU",
            usage = "桌面端 LanczosResampler 逐位复刻其 Resample.c 定点实现，用于与期望样本比对"
        )
    )

    /** 已知边界，README 与「关于」页保持一致（照实说）。 */
    val KNOWN_LIMITATIONS: List<String> = listOf(
        "纯代码 / 网页类壁纸、未导出的原始场景工程：包内没有现成 MP4 或纹理，无法提取",
        "TEXF 动帧容器（动态序列帧壁纸）暂不支持，遇到时跳过并提示",
        "ASTC / BC7（纹理 Format 12）可识别但不支持解码",
        "加密或非标准魔数的包按格式错误处理",
        "GPU 解码尚未实现，设置页中的开关仅占位；CPU 解码 4K 单张约 1–3 秒"
    )
}
