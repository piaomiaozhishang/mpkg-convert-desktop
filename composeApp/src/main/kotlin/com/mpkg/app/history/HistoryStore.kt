package com.mpkg.app.history

import com.mpkg.app.platform.PlatformPaths
import com.mpkg.app.util.AppLog
import com.mpkg.app.util.AtomicFile
import com.mpkg.app.util.JsonParser
import com.mpkg.app.util.JsonValue
import com.mpkg.app.util.JsonWriter
import com.mpkg.app.util.asList
import com.mpkg.app.util.asLong
import com.mpkg.app.util.asString
import com.mpkg.app.util.asStringOrNull
import com.mpkg.app.util.get
import java.io.File
import java.util.UUID

/** 一条历史记录（任务卡 T3.3）。 */
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val sourcePath: String,
    /** "image" 或 "video"。 */
    val kind: String,
    val timestamp: Long,
    val outputs: List<String>,
    /** 缩略图缓存文件名（在 [PlatformPaths.thumbnailCacheDir] 下）。 */
    val thumbFileName: String? = null,
    /** 缩略图最后一次被使用的时间，用于 LRU 淘汰。 */
    val thumbLastUsed: Long = 0L,
    val note: String? = null
) {
    val primaryOutput: String? get() = outputs.firstOrNull()

    /** 输出文件是否还在（不在则界面上置灰）。 */
    fun outputExists(): Boolean = outputs.any { File(it).isFile }
}

/**
 * 历史记录存储（任务卡 T3.3）。
 *
 * - `history.json` 存在平台配置目录，**原子写入**（临时文件 + rename）；
 * - 缩略图缓存有数量上限，按 LRU 淘汰；
 * - 手写 JSON 序列化，不引三方库。
 */
class HistoryStore(
    private val file: File = PlatformPaths.historyFile(),
    private val thumbDir: File = PlatformPaths.thumbnailCacheDir(),
    private val maxThumbnails: Int = MAX_THUMBNAILS
) {

    private val entries = ArrayList<HistoryEntry>()

    init {
        // 构造即加载：否则对一个新建实例调用 add() 会用空列表覆盖掉磁盘上的历史。
        load()
    }

    fun load(): List<HistoryEntry> {
        entries.clear()
        val text = AtomicFile.readTextOrNull(file)
        if (text != null) {
            JsonParser.parseOrNull(text)?.let { root ->
                root["entries"].asList().forEach { item ->
                    runCatching { readEntry(item) }.getOrNull()?.let { entries.add(it) }
                }
            }
        }
        entries.sortByDescending { it.timestamp }
        pruneThumbnails()
        return entries.toList()
    }

    /** 最新在前。 */
    fun all(): List<HistoryEntry> = entries.toList()

    fun add(entry: HistoryEntry) {
        entries.add(0, entry)
        if (entries.size > MAX_ENTRIES) {
            val removed = entries.subList(MAX_ENTRIES, entries.size).toList()
            entries.subList(MAX_ENTRIES, entries.size).clear()
            removed.forEach { it.thumbFileName?.let(::deleteThumb) }
        }
        persist()
        pruneThumbnails()
    }

    fun clear() {
        entries.clear()
        persist()
        thumbnailDir().listFiles()?.forEach { it.delete() }
    }

    /** 记下缩略图被使用，用于 LRU。 */
    fun touchThumbnail(id: String) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        if (entries[index].thumbFileName == null) return
        entries[index] = entries[index].copy(thumbLastUsed = System.currentTimeMillis())
        persist()
    }

    /** 把一张缩略图写进缓存目录，返回缓存文件名。 */
    fun saveThumbnail(id: String, bytes: ByteArray): String? = try {
        val dir = thumbnailDir()
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("无法创建缩略图目录: $dir")
        val name = "$id.png"
        File(dir, name).writeBytes(bytes)
        name
    } catch (e: Exception) {
        AppLog.w("保存缩略图失败: ${e.message}")
        null
    }

    fun thumbnailFile(entry: HistoryEntry): File? {
        val name = entry.thumbFileName ?: return null
        val f = File(thumbnailDir(), name)
        return if (f.isFile) f else null
    }

    fun thumbnailDir(): File = thumbDir

    /** 超过上限时按最后使用时间淘汰。 */
    fun pruneThumbnails() {
        val withThumbs = entries.filter { it.thumbFileName != null }
        if (withThumbs.size <= maxThumbnails) return
        val doomed = withThumbs.sortedBy { it.thumbLastUsed }.take(withThumbs.size - maxThumbnails)
        val doomedIds = doomed.map { it.id }.toSet()
        doomed.forEach { it.thumbFileName?.let(::deleteThumb) }
        for (i in entries.indices) {
            if (entries[i].id in doomedIds) {
                entries[i] = entries[i].copy(thumbFileName = null)
            }
        }
        persist()
    }

    private fun deleteThumb(name: String) {
        val f = File(thumbnailDir(), name)
        if (f.exists() && !f.delete()) AppLog.w("缩略图删除失败: ${f.absolutePath}")
    }

    private fun persist() {
        try {
            AtomicFile.writeText(file, JsonWriter.write(toJson()))
        } catch (e: Exception) {
            AppLog.e("保存历史记录失败: ${e.message}", e)
        }
    }

    private fun toJson(): JsonValue = JsonValue.obj(
        "version" to JsonValue.of(1),
        "entries" to JsonValue.arr(entries.map { e ->
            JsonValue.obj(
                "id" to JsonValue.of(e.id),
                "fileName" to JsonValue.of(e.fileName),
                "sourcePath" to JsonValue.of(e.sourcePath),
                "kind" to JsonValue.of(e.kind),
                "timestamp" to JsonValue.of(e.timestamp),
                "outputs" to JsonValue.arr(e.outputs.map { JsonValue.of(it) }),
                "thumbFileName" to JsonValue.of(e.thumbFileName),
                "thumbLastUsed" to JsonValue.of(e.thumbLastUsed),
                "note" to JsonValue.of(e.note)
            )
        })
    )

    private fun readEntry(node: JsonValue): HistoryEntry = HistoryEntry(
        id = node["id"].asString(UUID.randomUUID().toString()),
        fileName = node["fileName"].asString(),
        sourcePath = node["sourcePath"].asString(),
        kind = node["kind"].asString("image"),
        timestamp = node["timestamp"].asLong(0L),
        outputs = node["outputs"].asList().mapNotNull { it.asStringOrNull() },
        thumbFileName = node["thumbFileName"].asStringOrNull(),
        thumbLastUsed = node["thumbLastUsed"].asLong(0L),
        note = node["note"].asStringOrNull()
    )

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_THUMBNAILS = 120
    }
}
