package com.mpkg.app.tools

import com.doubao.repkg.core.PkgReader
import com.mpkg.app.errors.ErrorMessages
import com.mpkg.app.export.Exporter
import com.mpkg.app.export.OverwritePolicy
import com.mpkg.app.extract.DesktopExtractor
import com.mpkg.app.extract.OutputItem
import com.mpkg.app.extract.OutputPayload
import com.mpkg.app.image.LanczosResampler
import com.mpkg.app.image.PngIo
import com.mpkg.app.image.Raster
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 端到端命令行（任务卡 T1.3）。
 *
 * 先于任何 UI 存在：把 `.tex` / `.mpkg` / `.pkg` 解成 PNG / MP4，
 * 并可与 `sample/expected_1280x720.png` 做逐像素比对。
 *
 * ```
 * mpkg-cli <输入> [输出] [选项]
 * ```
 * 退出码：0 成功 / 1 处理失败 / 2 用法错误 / 3 包内没有可提取内容。
 */
object CliMain {

    private const val EXIT_OK = 0
    private const val EXIT_FAILED = 1
    private const val EXIT_USAGE = 2
    private const val EXIT_NO_CONTENT = 3

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    fun run(args: Array<String>): Int {
        if (args.isEmpty() || args.any { it == "-h" || it == "--help" }) {
            printUsage()
            return if (args.isEmpty()) EXIT_USAGE else EXIT_OK
        }

        var outDir: File? = null
        var outName: String? = null
        var scale: Pair<Int, Int>? = null
        var wantPreview = false
        var listOnly = false
        var wantHash = false
        var compareWith: File? = null
        var policy = OverwritePolicy.OVERWRITE
        val positional = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--out-dir" -> outDir = File(requireValue(args, ++i, a))
                "--scale", "--resize" -> scale = parseSize(requireValue(args, ++i, a))
                "--preview" -> wantPreview = true
                "--list" -> listOnly = true
                "--hash" -> wantHash = true
                "--compare" -> compareWith = File(requireValue(args, ++i, a))
                "--overwrite" -> policy = parsePolicy(requireValue(args, ++i, a))
                "--debug" -> com.mpkg.app.util.AppLog.debugEnabled = true
                else -> {
                    if (a.startsWith("--")) {
                        System.err.println("未知选项: $a")
                        printUsage()
                        return EXIT_USAGE
                    }
                    positional.add(a)
                }
            }
            i++
        }

        val inputArg = positional.getOrNull(0)
        if (inputArg == null) {
            printUsage()
            return EXIT_USAGE
        }
        val input = File(inputArg)
        if (!input.isFile) {
            System.err.println("输入文件不存在: ${input.absolutePath}")
            return EXIT_FAILED
        }
        positional.getOrNull(1)?.let {
            val f = File(it)
            if (it.endsWith("/") || it.endsWith("\\") || f.isDirectory) outDir = f else outName = it
        }

        return try {
            convert(input, outDir, outName, scale, wantPreview, listOnly, wantHash, compareWith, policy)
        } catch (e: Throwable) {
            System.err.println("错误: ${ErrorMessages.describe(e)}")
            com.mpkg.app.util.AppLog.e("CLI 失败: ${input.name}", e)
            EXIT_FAILED
        }
    }

    private fun convert(
        input: File,
        outDirArg: File?,
        outName: String?,
        scale: Pair<Int, Int>?,
        wantPreview: Boolean,
        listOnly: Boolean,
        wantHash: Boolean,
        compareWith: File?,
        policy: OverwritePolicy
    ): Int {
        val fileName = input.name.lowercase()
        val isPackage = fileName.endsWith(".mpkg") || fileName.endsWith(".pkg")

        if (listOnly) {
            return if (isPackage) listPackage(input) else listTex(input)
        }

        val outDir = outDirArg ?: File(".")
        val main: OutputItem?
        val preview: OutputItem?
        val isVideo: Boolean

        if (isPackage) {
            val analysis = DesktopExtractor.analyzePackage(input) { fraction, stage ->
                if (com.mpkg.app.util.AppLog.debugEnabled) {
                    com.mpkg.app.util.AppLog.d("阶段 ${(fraction * 100).toInt()}% $stage")
                }
            }
            System.out.println(
                "包: ${input.name}  magic=${analysis.magic}  条目=${analysis.entryCount}  " +
                    if (analysis.isVideo) "类型=视频壁纸" else "类型=静态壁纸"
            )
            main = analysis.main
            preview = analysis.preview
            isVideo = analysis.isVideo
        } else {
            val analysis = DesktopExtractor.analyzeTexFile(input)
            main = analysis.main
            preview = null
            isVideo = analysis.isVideo
        }

        if (main == null) {
            System.err.println("错误: ${ErrorMessages.NO_CONTENT}")
            return EXIT_NO_CONTENT
        }

        val targetName = outName ?: main.suggestedName
        val item = if (targetName == main.suggestedName) {
            main
        } else {
            OutputItem(main.kind, targetName, main.sourceEntry, main.payload, main.isPrimary, main.note)
        }

        val written = Exporter.write(item, outDir, policy)
            ?: run {
                System.err.println("已跳过（目标已存在）: ${File(outDir, targetName).name}")
                return EXIT_OK
            }
        System.out.println(
            "输出: ${written.absolutePath}  " +
                "(${sizeText(written.length())}${dimensionText(item)})"
        )
        if (wantHash) {
            System.out.println("SHA256: ${sha256(written)}")
        }

        if (wantPreview && preview != null) {
            val pw = Exporter.write(preview, outDir, policy)
            if (pw != null) System.out.println("预览: ${pw.absolutePath} (${sizeText(pw.length())})")
        }

        var exit = EXIT_OK
        val isImageOutput = written.extension.lowercase() in IMAGE_EXTENSIONS
        if (scale != null && isImageOutput) {
            val raster = PngIo.read(written)
            if (raster == null) {
                System.err.println("错误: 无法读取刚写出的图片用于缩放")
                exit = EXIT_FAILED
            } else {
                val (sw, sh) = scale
                // 负数表示「按倍率」写法：3 → 原尺寸的 1/3。
                val w = if (sw < 0) raster.width / -sw else sw
                val h = if (sh < 0) raster.height / -sh else sh
                require(w > 0 && h > 0) { "缩放后尺寸必须为正: ${w}x$h" }
                val scaledFile = File(written.parentFile, "${written.nameWithoutExtension}_${w}x$h.png")
                val scaled = LanczosResampler.resample(raster.argb, raster.width, raster.height, w, h)
                PngIo.writeArgb(scaled, w, h, scaledFile)
                System.out.println("缩放: ${scaledFile.absolutePath} (${w}x$h, Lanczos)")
                if (compareWith != null) {
                    exit = runCompare(scaledFile, compareWith)
                }
            }
        } else if (compareWith != null) {
            if (!isImageOutput) {
                System.err.println("提示: 视频输出不做像素比对")
            } else {
                exit = runCompare(written, compareWith)
            }
        }

        if (isVideo) {
            System.out.println("提示: 视频为包内字节原样导出，未重编码")
        }
        return exit
    }

    private fun runCompare(actual: File, expected: File): Int {
        return try {
            val r = PngCompare.compare(actual, expected)
            val total = r.width.toLong() * r.height
            System.out.println(
                "比对: ${actual.name} vs ${expected.name} → " +
                    "差异像素=${r.diffPixels}/$total, 最大通道差=${r.maxChannelDelta}" +
                    if (r.resampled) "（已 Lanczos 对齐尺寸）" else ""
            )
            if (r.pass) {
                System.out.println("PASS: 逐像素一致")
                EXIT_OK
            } else {
                System.out.println("FAIL")
                EXIT_FAILED
            }
        } catch (e: Exception) {
            System.err.println("比对失败: ${e.message}")
            EXIT_FAILED
        }
    }

    private fun listPackage(input: File): Int {
        FileChannel.open(input.toPath(), StandardOpenOption.READ).use { channel ->
            val archive = PkgReader.read(com.doubao.repkg.core.ChannelSeekableData(channel))
            System.out.println("magic=${archive.magic} 条目=${archive.entries.size} 头部=${archive.headerSize} 字节")
            archive.entries.forEach { e ->
                System.out.println("  off=${e.dataOffset} len=${e.length}  ${e.path}")
            }
        }
        return EXIT_OK
    }

    private fun listTex(input: File): Int {
        val peek = com.doubao.repkg.core.TexAssetDecoder.peek(input.readBytes(), mobileLayout = false)
        if (peek == null) {
            System.err.println("错误: ${ErrorMessages.NOT_A_TEXTURE}")
            return EXIT_FAILED
        }
        System.out.println(
            "tex: format=${peek.texFormat} ${formatName(peek.texFormat)} " +
                "尺寸=${peek.width}x${peek.height} imageFormat=${peek.imageFormat} " +
                "视频=${peek.isVideo}"
        )
        return EXIT_OK
    }

    private fun formatName(format: Int): String = when (format) {
        0 -> "(RGBA8888)"
        4 -> "(DXT5)"
        5 -> "(ETC2_RGBA8)"
        6 -> "(DXT3)"
        7 -> "(DXT1)"
        8 -> "(RG88)"
        9 -> "(R8)"
        else -> "(未知)"
    }

    private fun dimensionText(item: OutputItem): String = when (val p = item.payload) {
        is OutputPayload.Pixels -> "  ${p.width}x${p.height}"
        is OutputPayload.StreamSlice -> "  包内 ${p.offset}+${p.length}"
        is OutputPayload.Bytes -> ""
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireValue(args: Array<String>, index: Int, option: String): String {
        if (index >= args.size) throw IllegalArgumentException("选项 $option 缺少参数")
        return args[index]
    }

    /** 支持 `1280x720` 与 `3`（整数倍下采样）两种写法。 */
    private fun parseSize(text: String): Pair<Int, Int> {
        val lower = text.lowercase()
        if ('x' in lower) {
            val parts = lower.split('x')
            val w = parts[0].trim().toInt()
            val h = parts[1].trim().toInt()
            require(w > 0 && h > 0) { "尺寸必须为正: $text" }
            return w to h
        }
        val factor = lower.toInt()
        require(factor > 0) { "倍率必须为正: $text" }
        return -factor to -factor
    }

    private fun parsePolicy(text: String): OverwritePolicy = when (text.lowercase()) {
        "skip" -> OverwritePolicy.SKIP
        "overwrite" -> OverwritePolicy.OVERWRITE
        "rename" -> OverwritePolicy.RENAME
        else -> throw IllegalArgumentException("未知覆盖策略: $text（可选 skip/overwrite/rename）")
    }

    private fun printUsage() {
        System.out.println(
            """
            用法: mpkg-cli <输入> [输出] [选项]

            输入: .mpkg / .pkg / .tex / scene.pkg
            输出: .png（图片）或 .mp4（视频）；省略时按包内名称写到当前目录

            选项:
              --out-dir <目录>        输出目录（默认当前目录；不存在会自动创建）
              --scale <宽x高|倍率>    额外导出一份缩放图（Pillow 兼容 Lanczos）
              --preview               同时导出预览图
              --compare <期望图.png>  与期望图逐像素比对，输出 PASS / FAIL
              --list                  只列出包内条目，不导出
              --hash                  打印输出的 SHA-256（用于 MP4 字节一致性验收）
              --overwrite <策略>      skip / overwrite / rename（默认 overwrite）
              --debug                 打印调试日志与异常堆栈
              -h, --help              显示本帮助

            示例:
              mpkg-cli sample/texture.tex out.png --scale 1280x720 --compare sample/expected_1280x720.png
              mpkg-cli 壁纸.mpkg --out-dir out --hash
            """.trimIndent()
        )
    }
}

/**
 * `mpkg-cli.jar` 的入口。
 *
 * 与 GUI 入口分开，使命令行版的 jar 不必带上 Compose 依赖，
 * 体积小、启动快，便于脚本与 CI 使用。
 */
fun main(args: Array<String>) {
    kotlin.system.exitProcess(CliMain.run(args))
}
