package com.mpkg.app.queue

import com.mpkg.app.errors.ErrorMessages
import com.mpkg.app.export.Exporter
import com.mpkg.app.export.SceneExporter
import com.mpkg.app.extract.DesktopExtractor
import com.mpkg.app.extract.OutputItem
import com.mpkg.app.extract.OutputPayload
import com.mpkg.app.settings.AppSettings
import com.mpkg.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * 解码编排（任务卡 T2.4）。
 *
 * - 每个文件一个协程 [Job]，成功/失败都只影响自己（失败隔离）；
 * - 并发度可配（默认 2），运行中改设置对**后续**任务生效；
 * - 取消即时反映在状态上；ETC2 解码是 core 的单次不可中断调用，会在该次调用
 *   返回后立刻放弃后续阶段（见 [cancel] 注释）；
 * - 进度按文件阶段汇报，配合 UI 的平滑动画，不会出现 0% 直跳 100%。
 */
class ConversionQueue(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AppSettings,
    private val onUpdate: (ConversionTask) -> Unit,
    private val onCompleted: (ConversionTask) -> Unit = {},
    /**
     * 把包内预览图字节存进缩略图缓存，返回缓存文件的绝对路径；返回 null 表示没存成。
     *
     * 缩略图只落盘、不常驻任务对象，避免任务列表随文件数线性吃掉内存。
     */
    private val storeThumbnail: (String, ByteArray) -> String? = { _, _ -> null }
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 并发闸门：信号量固定按最大并发度创建，通过**长期占用**一部分许可来把上限
     * 降到当前设置值。这样改设置不需要换信号量——换信号量会让「旧任务持旧许可、
     * 新任务取新许可」同时成立，实际并发反而超过设置值。
     */
    private val slots = Semaphore(AppSettings.MAX_CONCURRENCY)
    private val reservationMutex = Mutex()
    private var reserved = 0

    /** 入队并立即开始调度。 */
    fun submit(task: ConversionTask) {
        onUpdate(task)
        val job = scope.launch {
            acquirePermit {
                runTask(task)
            }
        }
        jobs[task.id] = job
        job.invokeOnCompletion { jobs.remove(task.id) }
    }

    /**
     * 取消单个任务：状态立刻置为「已取消」，界面马上响应。
     *
     * core 的解码是不可中断的单次调用，因此真正的线程会在当前解码返回后退出；
     * 退出后不会再写出任何文件。
     */
    fun cancel(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
    }

    fun cancelAll() {
        jobs.keys.toList().forEach(::cancel)
    }

    fun isRunning(id: String): Boolean = jobs.containsKey(id)

    private suspend fun acquirePermit(block: suspend () -> Unit) {
        syncReservation()
        slots.withPermit { block() }
    }

    /** 把「最大并发度 - 当前设置」个许可长期占住，等价于把并发上限降到当前设置值。 */
    private suspend fun syncReservation() {
        val wanted = settingsProvider().concurrency.coerceIn(
            AppSettings.MIN_CONCURRENCY, AppSettings.MAX_CONCURRENCY
        )
        val wantReserved = AppSettings.MAX_CONCURRENCY - wanted
        reservationMutex.withLock {
            while (reserved < wantReserved) {
                slots.acquire()
                reserved++
            }
            while (reserved > wantReserved) {
                slots.release()
                reserved--
            }
        }
    }

    private suspend fun runTask(task: ConversionTask) {
        val startedAt = System.currentTimeMillis()
        // 进度回调跑在 IO 线程上，用 AtomicReference 承载，避免跨线程读写普通 var。
        val current = AtomicReference(
            task.copy(status = TaskStatus.RUNNING, progress = 0f, stage = "准备中")
        )
        onUpdate(current.get())

        fun update(fraction: Float, stage: String) {
            val next = current.get().copy(progress = fraction.coerceIn(0f, 1f), stage = stage)
            current.set(next)
            onUpdate(next)
        }

        try {
            val settings = settingsProvider()
            val outDir = File(settings.outputDir)

            val analysis = withContext(Dispatchers.IO) {
                val jobContext = coroutineContext
                DesktopExtractor.analyzePackage(task.file) { fraction, stage ->
                    jobContext.ensureActive()
                    update(fraction, stage)
                }
            }
            coroutineContext.ensureActive()

            val main = analysis.main
            if (main == null) {
                val next = current.get().copy(
                    status = TaskStatus.FAILED,
                    progress = 1f,
                    stage = "无可提取内容",
                    errorTitle = ErrorMessages.NO_CONTENT,
                    errorDetail = null,
                    packageMagic = analysis.magic,
                    entryCount = analysis.entryCount,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
                current.set(next)
                onUpdate(next)
                onCompleted(next)
                return
            }

            val isScene = main.payload is OutputPayload.Scene
            update(if (isScene) 0.45f else 0.92f, if (isScene) "准备合成壁纸" else "写出文件")
            val written = withContext(Dispatchers.IO) {
                val jobContext = coroutineContext
                val list = ArrayList<TaskOutput>(2)
                Exporter.write(main, outDir, settings.overwritePolicy, progress = { n, total, message ->
                    update(0.45f + 0.45f * n / total.coerceAtLeast(1), message)
                }, checkActive = { jobContext.ensureActive() })?.let { file ->
                    list.add(toTaskOutput(file, main, isPreview = false))
                }
                if (settings.exportPreview) {
                    analysis.preview?.let { preview ->
                        Exporter.write(preview, outDir, settings.overwritePolicy)?.let { file ->
                            list.add(toTaskOutput(file, preview, isPreview = true))
                        }
                    }
                }
                list
            }
            coroutineContext.ensureActive()

            // 缩略图直接落盘，任务对象只留文件名（O4：不把数百 KB 的预览字节常驻内存）。
            val thumbPath = withContext(Dispatchers.IO) {
                val bytes = if (isScene) written.firstOrNull()?.let { output ->
                    runCatching { SceneExporter.thumbnail(File(output.path)) }.getOrNull()
                } else (analysis.preview?.payload as? OutputPayload.Bytes)?.data
                bytes?.let { storeThumbnail(task.id, it) }
            }

            // 覆盖策略为「跳过」时 Exporter.write 返回 null，此时不能谎报「已完成」（M9）。
            val skipped = written.isEmpty()
            val next = current.get().copy(
                status = TaskStatus.DONE,
                progress = 1f,
                stage = if (skipped) "已跳过" else "已完成",
                packageMagic = analysis.magic,
                entryCount = analysis.entryCount,
                isVideo = analysis.isVideo,
                outputs = written,
                primaryOutputPath = written.firstOrNull { !it.isPreview }?.path
                    ?: File(outDir, main.suggestedName).takeIf { it.isFile }?.absolutePath,
                thumbnailPath = thumbPath,
                note = if (skipped) "目标文件已存在，按覆盖策略跳过" else main.note.takeIf { isScene },
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            current.set(next)
            onUpdate(next)
            onCompleted(next)
        } catch (e: CancellationException) {
            val next = current.get().copy(
                status = TaskStatus.CANCELLED,
                stage = "已取消",
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            current.set(next)
            onUpdate(next)
            onCompleted(next)
            throw e
        } catch (e: Throwable) {
            AppLog.e("转换失败: ${task.fileName}", e)
            val next = current.get().copy(
                status = TaskStatus.FAILED,
                progress = 1f,
                stage = "失败",
                errorTitle = ErrorMessages.describe(e),
                errorDetail = e.message,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            current.set(next)
            onUpdate(next)
            onCompleted(next)
        }
    }

    private fun toTaskOutput(file: File, item: OutputItem, isPreview: Boolean): TaskOutput {
        val width: Int
        val height: Int
        when (val payload = item.payload) {
            is OutputPayload.Pixels -> {
                width = payload.width
                height = payload.height
            }
            is OutputPayload.Scene -> {
                width = payload.scene.info.canvasWidth
                height = payload.scene.info.canvasHeight
            }
            else -> {
                width = 0
                height = 0
            }
        }
        return TaskOutput(
            path = file.absolutePath,
            fileName = file.name,
            kind = item.kind.name,
            bytes = file.length(),
            width = width,
            height = height,
            isPreview = isPreview
        )
    }
}
