package com.mpkg.app.settings

import com.mpkg.app.export.OverwritePolicy
import com.mpkg.app.ui.theme.AccentPreset
import com.mpkg.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 设置持久化（任务卡 T3.4）：往返一致、损坏回退、边界收敛。 */
class SettingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): SettingsStore = SettingsStore(File(temp.root, "settings.json"))

    @Test
    fun `默认值面向零配置可用`() {
        val defaults = AppSettings()
        assertEquals(AppSettings.DEFAULT_CONCURRENCY, defaults.concurrency)
        assertEquals(OverwritePolicy.RENAME, defaults.overwritePolicy)
        assertEquals(ThemeMode.DARK, defaults.themeMode)
        assertEquals(AccentPreset.CLASSIC_BLUE, defaults.accent)
        assertFalse(defaults.exportPreview)
        assertTrue(defaults.outputDir.isNotBlank())
        assertEquals(WindowState.DEFAULT_WIDTH, defaults.window.width)
    }

    @Test
    fun `保存后重新加载完全一致`() {
        val custom = AppSettings(
            outputDir = """D:\壁纸\输出""",
            overwritePolicy = OverwritePolicy.SKIP,
            concurrency = 4,
            exportPreview = true,
            decodeMode = DecodeMode.GPU,
            themeMode = ThemeMode.SYSTEM,
            accent = AccentPreset.VIOLET,
            reduceMotion = true,
            openOutputWhenDone = true,
            autoCheckUpdate = true,
            updateCheckUrl = "https://example.com/update.json",
            updateDownloadUrl = "https://example.com/app.zip",
            lastUpdateCheckAt = 1_700_000_000_000L,
            lastUpdateCheckOk = true,
            ignoredUpdateVersion = "1.0.1",
            ignoredUpdateAt = 1_700_000_100_000L,
            window = WindowState(x = 120, y = 80, width = 1200, height = 800, maximized = true)
        )
        store().save(custom)
        assertEquals(custom.normalized(), store().load())
    }

    @Test
    fun `忽略版本只在12小时内有效_过期自动恢复`() {
        val now = 10_000_000L
        val interval = 12L * 60 * 60 * 1000
        val ignored = AppSettings(ignoredUpdateVersion = "1.0.1", ignoredUpdateAt = now)
        assertTrue(ignored.ignoreActiveFor("1.0.1", now + interval - 1, interval))
        assertFalse("满 12 小时应恢复提醒", ignored.ignoreActiveFor("1.0.1", now + interval, interval))
        assertFalse(ignored.ignoreActiveFor("1.0.1", now + interval + 60_000, interval))
        assertFalse("换了版本号不再压制", ignored.ignoreActiveFor("1.0.2", now + 1000, interval))
        assertFalse("没忽略过任何版本", AppSettings().ignoreActiveFor("1.0.1", now, interval))
    }

    @Test
    fun `检查结果三态可往返_缺失即从未检查`() {
        val store = store()
        store.save(AppSettings(lastUpdateCheckAt = 123L, lastUpdateCheckOk = false))
        assertEquals(false, store.load().lastUpdateCheckOk)
        store.save(AppSettings(lastUpdateCheckAt = 123L, lastUpdateCheckOk = true))
        assertEquals(true, store.load().lastUpdateCheckOk)
        store.save(AppSettings())
        assertNull(store.load().lastUpdateCheckOk)
        assertEquals(0L, store.load().ignoredUpdateAt)
    }

    @Test
    fun `设置文件损坏时回退默认值而不是崩溃`() {
        val file = File(temp.root, "settings.json")
        file.writeText("{ 这不是合法 JSON ", Charsets.UTF_8)
        assertEquals(AppSettings(), SettingsStore(file).load())
    }

    @Test
    fun `并发数会被收敛到合法区间`() {
        assertEquals(AppSettings.MAX_CONCURRENCY, AppSettings(concurrency = 999).normalized().concurrency)
        assertEquals(AppSettings.MIN_CONCURRENCY, AppSettings(concurrency = 0).normalized().concurrency)
        assertEquals(AppSettings.MIN_CONCURRENCY, AppSettings(concurrency = -5).normalized().concurrency)
    }

    @Test
    fun `未知枚举值回退默认`() {
        val file = File(temp.root, "settings.json")
        file.writeText("""{"themeMode":"NEON","accent":"RAINBOW","decodeMode":"QUANTUM"}""", Charsets.UTF_8)
        val loaded = SettingsStore(file).load()
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertEquals(AccentPreset.CLASSIC_BLUE, loaded.accent)
        assertEquals(DecodeMode.CPU, loaded.decodeMode)
    }

    @Test
    fun `写入是原子的_不留下临时文件`() {
        val file = File(temp.root, "settings.json")
        val store = SettingsStore(file)
        repeat(5) { store.save(AppSettings(concurrency = it + 1)) }
        assertTrue(file.isFile)
        val leftovers = temp.root.listFiles()?.filter { it.name.contains(".tmp") }.orEmpty()
        assertTrue("不应留下临时文件: $leftovers", leftovers.isEmpty())
    }
}
