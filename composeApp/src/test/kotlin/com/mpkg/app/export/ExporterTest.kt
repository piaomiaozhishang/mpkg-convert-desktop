package com.mpkg.app.export

import com.doubao.repkg.core.AssetKind
import com.mpkg.app.extract.OutputItem
import com.mpkg.app.extract.OutputPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** 导出与覆盖策略（任务卡 T2.5）。 */
class ExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bytesItem(name: String, data: ByteArray) = OutputItem(
        kind = AssetKind.VIDEO,
        suggestedName = name,
        sourceEntry = name,
        payload = OutputPayload.Bytes(data),
        isPrimary = true,
        note = null
    )

    @Test
    fun `已存在时三种策略行为正确`() {
        val dir = temp.newFolder("out")
        File(dir, "a.bin").writeBytes(byteArrayOf(1))
        val item = bytesItem("a.bin", byteArrayOf(2))

        assertNull("跳过策略不应写出", Exporter.write(item, dir, OverwritePolicy.SKIP))
        assertEquals(1, File(dir, "a.bin").readBytes()[0].toInt())

        val overwritten = Exporter.write(item, dir, OverwritePolicy.OVERWRITE)
        assertEquals("a.bin", overwritten?.name)
        assertArrayEquals(byteArrayOf(2), File(dir, "a.bin").readBytes())

        val renamed = Exporter.write(item, dir, OverwritePolicy.RENAME)
        assertEquals("a_1.bin", renamed?.name)
        // RENAME 不影响已有文件（它此时已被 OVERWRITE 改为 2），新内容写到 a_1.bin。
        assertArrayEquals(byteArrayOf(2), File(dir, "a.bin").readBytes())
        assertArrayEquals(byteArrayOf(2), File(renamed!!.absolutePath).readBytes())
    }

    @Test
    fun `重命名在多次冲突时递增序号`() {
        val dir = temp.newFolder("out2")
        val item = bytesItem("x.dat", byteArrayOf(9))
        Exporter.write(item, dir, OverwritePolicy.OVERWRITE)
        Exporter.write(item, dir, OverwritePolicy.RENAME)
        val third = Exporter.write(item, dir, OverwritePolicy.RENAME)
        assertEquals("x_2.dat", third?.name)
    }

    @Test
    fun `输出目录不存在时自动创建`() {
        val dir = File(temp.root, "deep/nested/out")
        val written = Exporter.write(bytesItem("y.bin", byteArrayOf(3)), dir, OverwritePolicy.OVERWRITE)
        assertTrue(dir.isDirectory)
        assertTrue(written!!.isFile)
    }

    @Test
    fun `流式复制与包内字节完全一致`() {
        val source = File(temp.root, "pkg.bin")
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        source.writeBytes(ByteArray(1024) + payload + ByteArray(512))
        val target = File(temp.root, "out/copy.mp4")
        target.parentFile.mkdirs()

        Exporter.streamCopy(OutputPayload.StreamSlice(source, 1024, payload.size.toLong()), target)

        assertEquals(payload.size.toLong(), target.length())
        assertArrayEquals(payload, target.readBytes())
        assertEquals(sha256(payload), sha256(target.readBytes()))
    }

    @Test
    fun `流式复制在源数据不足时抛异常而不是写出半截文件`() {
        val source = File(temp.root, "short.bin")
        source.writeBytes(ByteArray(100))
        val target = File(temp.root, "out/short.mp4")
        target.parentFile.mkdirs()
        var threw = false
        try {
            Exporter.streamCopy(OutputPayload.StreamSlice(source, 50, 1000), target)
        } catch (e: java.io.EOFException) {
            threw = true
        }
        assertTrue("应当抛出 EOFException", threw)
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
