package com.mpkg.app.queue

import com.doubao.repkg.core.AssetKind
import com.doubao.repkg.core.ByteArraySeekableData
import com.doubao.repkg.core.FormatException
import com.mpkg.app.extract.DesktopExtractor
import com.mpkg.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 解码编排（任务卡 T2.4）：状态机推进、失败隔离、进度阶段。
 *
 * 用现场构造的小包，不依赖 scripts 生成的夹具，保证单元测试自足。
 */
class ConversionQueueTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun le32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun i32s(b: ByteArray) = le32(b.size) + b

    /** 8x8 RGBA8888 纹理（TEXB0003, format=0），不需要 LZ4。 */
    private fun rawTex(w: Int, h: Int): ByteArray {
        val px = ByteArray(w * h * 4) { (it % 251).toByte() }
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0) + le32(0)
        out += le32(w) + le32(h) + le32(w) + le32(h) + le32(0)
        out += "TEXB0003\u0000".toByteArray()
        out += le32(1) + le32(-1) + le32(1)
        out += le32(w) + le32(h)
        out += le32(0) + le32(px.size) + le32(px.size)
        return out + px
    }

    private fun makePackage(magic: String, entries: List<Pair<String, ByteArray>>): ByteArray {
        var header = i32s(magic.toByteArray())
        header += le32(entries.size)
        var data = ByteArray(0)
        var cursor = 0
        for ((path, blob) in entries) {
            header += i32s(path.toByteArray())
            header += le32(cursor) + le32(blob.size)
            data += blob
            cursor += blob.size
        }
        return header + data
    }

    @Test
    fun `成功任务会走到已完成并写出文件`() = runBlocking {
        val pkg = File(temp.root, "ok.mpkg")
        pkg.writeBytes(makePackage("PKGM0019", listOf("textures/main.tex" to rawTex(8, 8))))
        val outDir = temp.newFolder("out")
        val updates = ConcurrentLinkedQueue<ConversionTask>()

        val queue = newQueue(outDir, updates)
        queue.submit(ConversionTask(file = pkg))
        awaitAnyTask(updates) { it.status == TaskStatus.DONE }

        val final = updates.last { it.status == TaskStatus.DONE }
        assertEquals(1f, final.progress, 0.001f)
        assertTrue(final.outputs.isNotEmpty())
        assertTrue(File(final.outputs.first().path).isFile)
        assertNull(final.errorTitle)
    }

    @Test
    fun `单个文件失败不影响其它任务`() = runBlocking {
        val good = File(temp.root, "good.mpkg")
        good.writeBytes(makePackage("PKGM0019", listOf("textures/main.tex" to rawTex(8, 8))))
        val broken = File(temp.root, "broken.mpkg")
        broken.writeBytes("这不是一个包".toByteArray())

        val outDir = temp.newFolder("out2")
        val updates = ConcurrentLinkedQueue<ConversionTask>()
        val queue = newQueue(outDir, updates)
        queue.submit(ConversionTask(file = broken))
        queue.submit(ConversionTask(file = good))
        awaitAllTasks(updates) { list ->
            list.count { it.status == TaskStatus.DONE } >= 1 && list.any { it.status == TaskStatus.FAILED }
        }

        val failed = updates.last { it.status == TaskStatus.FAILED }
        assertNotNull(failed.errorTitle)
        assertTrue("失败文案应可读", failed.errorTitle!!.isNotBlank())
        assertEquals(1, updates.count { it.status == TaskStatus.DONE })
    }

    @Test
    fun `进度按阶段推进_不会从 0 直接跳到 1`() = runBlocking {
        val pkg = File(temp.root, "steps.mpkg")
        pkg.writeBytes(makePackage("PKGM0019", listOf("textures/main.tex" to rawTex(16, 16))))
        val updates = ConcurrentLinkedQueue<ConversionTask>()
        val queue = newQueue(temp.newFolder("out3"), updates)
        queue.submit(ConversionTask(file = pkg))
        awaitAnyTask(updates) { it.status == TaskStatus.DONE }

        val fractions = updates.map { it.progress }.distinct().sorted()
        assertTrue("应能看到中间进度，实际: $fractions", fractions.size >= 3)
        assertTrue("首个进度应远小于 1", fractions.first() < 0.9f)
        assertTrue("阶段文案不应为空", updates.all { it.stage.isNotBlank() })
    }

    @Test
    fun `任务包信息被回填`() = runBlocking {
        val pkg = File(temp.root, "info.mpkg")
        pkg.writeBytes(makePackage("PKGV0005", listOf("textures/main.tex" to rawTex(8, 8))))
        val updates = ConcurrentLinkedQueue<ConversionTask>()
        val queue = newQueue(temp.newFolder("out4"), updates)
        queue.submit(ConversionTask(file = pkg))
        awaitAnyTask(updates) { it.status == TaskStatus.DONE }
        val final = updates.last { it.status == TaskStatus.DONE }
        assertEquals("PKGV0005", final.packageMagic)
        assertEquals(1, final.entryCount)
    }

    @Test
    fun `没有可提取内容时给出友好文案而不是异常`() = runBlocking {
        val pkg = File(temp.root, "empty.mpkg")
        pkg.writeBytes(makePackage("PKGM0019", listOf("project.json" to "{}".toByteArray())))
        val updates = ConcurrentLinkedQueue<ConversionTask>()
        val queue = newQueue(temp.newFolder("out5"), updates)
        queue.submit(ConversionTask(file = pkg))
        awaitAnyTask(updates) { it.status == TaskStatus.FAILED }
        assertEquals(com.mpkg.app.errors.ErrorMessages.NO_CONTENT, updates.last().errorTitle)
    }

    @Test fun skippedExistingOutputRemainsLocatable() = runBlocking {
        val pkg = temp.newFile("skip.mpkg")
        pkg.writeBytes(makePackage("PKGM0019", listOf("textures/main.tex" to rawTex(8, 8))))
        val outDir = temp.newFolder("skip-out")
        val main = requireNotNull(DesktopExtractor.analyzePackage(pkg).main)
        val existing = File(outDir, main.suggestedName).apply { writeText("keep existing") }
        val updates = ConcurrentLinkedQueue<ConversionTask>()
        val queue = newQueue(outDir, updates, com.mpkg.app.export.OverwritePolicy.SKIP)
        queue.submit(ConversionTask(file = pkg))
        awaitAnyTask(updates) { it.status == TaskStatus.DONE }
        val done = updates.last { it.status == TaskStatus.DONE }
        assertEquals(existing.absolutePath, done.primaryOutputPath)
        assertTrue(done.outputs.isEmpty())
        assertEquals("keep existing", existing.readText())
    }

    private fun newQueue(outDir: File, updates: ConcurrentLinkedQueue<ConversionTask>,
                         policy: com.mpkg.app.export.OverwritePolicy = com.mpkg.app.export.OverwritePolicy.RENAME): ConversionQueue {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return ConversionQueue(
            scope = scope,
            settingsProvider = { AppSettings(outputDir = outDir.absolutePath, concurrency = 2, overwritePolicy = policy) },
            onUpdate = { updates.add(it) }
        )
    }

    /** 等到「某一个任务」满足条件。 */
    private suspend fun awaitAnyTask(
        updates: ConcurrentLinkedQueue<ConversionTask>,
        timeoutMs: Long = 20_000,
        predicate: (ConversionTask) -> Boolean
    ) {
        withTimeout(timeoutMs) {
            while (!updates.any(predicate)) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /** 等到「所有任务的最新状态」满足条件。 */
    private suspend fun awaitAllTasks(
        updates: ConcurrentLinkedQueue<ConversionTask>,
        timeoutMs: Long = 20_000,
        predicate: (List<ConversionTask>) -> Boolean
    ) {
        withTimeout(timeoutMs) {
            while (!predicate(latest(updates))) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /** 同一 id 的最新一条状态。 */
    private fun latest(updates: ConcurrentLinkedQueue<ConversionTask>): List<ConversionTask> =
        updates.groupBy { it.id }.map { (_, list) -> list.last() }
}

/** 顺带覆盖 core 适配层对异常的处理（不需要 Android 环境）。 */
class DesktopExtractorTest {

    @Test
    fun `非包文件抛出 FormatException`() {
        val file = File.createTempFile("not-a-pkg", ".mpkg")
        try {
            file.writeBytes(ByteArray(64) { 7 })
            var threw = false
            try {
                DesktopExtractor.analyzePackage(file)
            } catch (e: FormatException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `空字节源读出条目数上限校验`() {
        var threw = false
        try {
            com.doubao.repkg.core.PkgReader.read(ByteArraySeekableData(ByteArray(0)))
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `任务摘要格式可读`() {
        val task = ConversionTask(
            file = File("x.mpkg"),
            fileName = "x.mpkg",
            fileSize = 2_500_000,
            packageMagic = "PKGM0019",
            entryCount = 12
        )
        assertTrue(task.packageSummary().contains("PKGM0019"))
        assertTrue(task.packageSummary().contains("12 个条目"))
        assertEquals(AssetKind.IMAGE.name, AssetKind.IMAGE.name)
    }
}
