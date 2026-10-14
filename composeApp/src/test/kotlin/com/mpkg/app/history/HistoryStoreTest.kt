package com.mpkg.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 历史记录（任务卡 T3.3）：持久化、清空、缩略图 LRU 上限。 */
class HistoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(maxThumbs: Int = 3): HistoryStore = HistoryStore(
        file = File(temp.root, "history.json"),
        thumbDir = File(temp.root, "thumbs"),
        maxThumbnails = maxThumbs
    )

    private fun entry(name: String, ts: Long = System.currentTimeMillis()) = HistoryEntry(
        fileName = name,
        sourcePath = """D:\in\$name""",
        kind = "image",
        timestamp = ts,
        outputs = listOf("""D:\out\$name.png""")
    )

    @Test
    fun `重启后历史仍在`() {
        store().add(entry("a.mpkg"))
        store().add(entry("b.mpkg"))
        val reloaded = store().load()
        assertEquals(2, reloaded.size)
        assertEquals("b.mpkg", reloaded.first().fileName)
    }

    @Test
    fun `清空后不再有记录与缩略图`() {
        val s = store()
        val e = entry("a.mpkg")
        s.add(e)
        s.saveThumbnail(e.id, byteArrayOf(1, 2, 3))
        s.clear()
        assertTrue(store().load().isEmpty())
        assertTrue(store().thumbnailDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `缩略图超过上限按最近使用时间淘汰`() {
        val s = store(maxThumbs = 2)
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        entries.forEachIndexed { index, e ->
            s.add(e)
            s.saveThumbnail(e.id, byteArrayOf(index.toByte()))
            Thread.sleep(5)
            s.touchThumbnail(e.id)
        }
        Thread.sleep(5)
        s.touchThumbnail(entries[2].id)
        s.pruneThumbnails()

        val kept = s.all().count { it.thumbFileName != null }
        assertTrue("缩略图数量应被限制在上限内，实际 $kept", kept <= 2)
        assertNull(s.all().first { it.id == entries[0].id }.thumbFileName)
    }

    @Test
    fun `输出文件不存在时能被判定为失效`() {
        assertTrue(!entry("gone.mpkg").outputExists())
    }

    @Test
    fun `历史文件损坏时不崩溃`() {
        File(temp.root, "history.json").writeText("### not json ###", Charsets.UTF_8)
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `主输出取第一个非预览路径`() {
        assertEquals("""D:\out\x.mpkg.png""", entry("x.mpkg").primaryOutput)
    }
}
