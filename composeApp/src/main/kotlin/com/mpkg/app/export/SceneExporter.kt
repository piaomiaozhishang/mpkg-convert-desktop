package com.mpkg.app.export

import com.mpkg.app.extract.OutputItem
import com.mpkg.app.extract.OutputPayload
import com.mpkg.scene.desktop.DesktopSceneCompositor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.concurrent.withLock

/** Serializes large scene canvases and commits a PNG only after the complete render succeeds. */
object SceneExporter {
    private val lock = ReentrantLock()

    /** Existing output policy is preserved; cancellation leaves the previous destination intact. */
    fun write(item: OutputItem, dir: File, policy: OverwritePolicy,
              progress: (Int, Int, String) -> Unit, checkActive: () -> Unit): File? = lock.withLock {
        checkActive()
        val payload = item.payload as OutputPayload.Scene
        val target = Exporter.resolveTarget(dir, item.suggestedName, policy) ?: return null
        val image = DesktopSceneCompositor.renderFile(payload.source, payload.scene) { n, total, name ->
            checkActive()
            progress(n, total, "合成图层：$name")
        }
        try {
            checkActive()
            val temp = Files.createTempFile(target.toPath().toAbsolutePath().parent, ".scene-", ".png")
            try {
                Files.newOutputStream(temp).use { check(ImageIO.write(image, "png", it)) { "PNG 编码失败" } }
                checkActive()
                if (policy == OverwritePolicy.OVERWRITE) {
                    Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else Files.move(temp, target.toPath())
            } finally { Files.deleteIfExists(temp) }
            target
        } finally { image.flush() }
    }

    /** Read a bounded thumbnail of the saved composite for task and history previews. */
    fun thumbnail(file: File): ByteArray = FileImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("无法读取合成图")
        try {
            reader.input = input
            val sample = ((maxOf(reader.getWidth(0), reader.getHeight(0)) + 511) / 512).coerceAtLeast(1)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val image = reader.read(0, param)
            try {
                ByteArrayOutputStream().use { out ->
                    check(ImageIO.write(image, "png", out))
                    out.toByteArray()
                }
            } finally { image.flush() }
        } finally { reader.dispose() }
    }
}
