package com.mpkg.app.queue

import com.mpkg.app.errors.ErrorMessages
import com.mpkg.app.export.Exporter
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 解码编排（任务卡 T2.4）。
 *
 * - 每个文件一个协程 [Job]，成功/失败都只影响自己（失败隔离）；
 * - 并发度可配（默认 2），运行中改设置会重建信号量；
 * - 取消即时反映在状态上；ETC2 解码是 core 的单次不可中断调用，会在该次调用
 *   返回后立刻放弃后续阶段（见 [cancel] 注释）；
 * - 进度按文件阶段汇报，配合 UI 的平滑动画，不会出现 0% 直跳 100%。
 */
class ConversionQueue(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AppSettings,
    private val onUpdate: (ConversionTask) -> Unit,
    private val onCompleted: (ConversionTask) -> Unit = {}
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var semaphore = Semaphore(settingsProvider().concurrency)

    @Volatile
    private var permits = settingsProvider().concurrency

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
        val current = currentSemaphore()
        current.withPermit { block() }
    }

    private fun currentSemaphore(): Semaphore {
        val wanted = settingsProvider().concurrency.coerceIn(
            AppSettings.MIN_CONCURRENCY, AppSettings.MAX_CONCURRENCY
        )
        if (wanted != permits) {
            // 运行中改并发：新建信号量，已持有的许可自然释放后收敛到新值。
            semaphore = Semaphore(wanted)
            permits = wanted
            AppLog.i("并发度调整为 $wanted")
        }
        return semaphore
    }

    private suspend fun runTask(task: ConversionTask) {
        val startedAt = System.currentTimeMillis()
        var current = task.copy(status = TaskStatus.RUNNING, progress = 0f, stage = "准备中")
        onUpdate(current)

        fun update(fraction: Float, stage: String) {
            current = current.copy(progress = fraction.coerceIn(0f, 1f), stage = stage)
            onUpdate(current)
        }

        try {
            val settings = settingsProvider()
            val outDir = File(settings.outputDir)

            val analysis = withContext(Dispatchers.IO) {
                DesktopExtractor.analyzePackage(task.file) { fraction, stage ->
                    update(fraction, stage)
                }
            }
            coroutineContext.ensureActive()

            val main = analysis.main
            if (main == null) {
                current = current.copy(
                    status = TaskStatus.FAILED,
                    progress = 1f,
                    stage = "无可提取内容",
                    errorTitle = ErrorMessages.NO_CONTENT,
                    errorDetail = null,
                    packageMagic = analysis.magic,
                    entryCount = analysis.entryCount,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
                onUpdate(current)
                onCompleted(current)
                return
            }

            update(0.92f, "写出文件")
            val written = withContext(Dispatchers.IO) {
                val list = ArrayList<TaskOutput>(2)
                Exporter.write(main, outDir, settings.overwritePolicy)?.let { file ->
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

            current = current.copy(
                status = TaskStatus.DONE,
                progress = 1f,
                stage = "已完成",
                packageMagic = analysis.magic,
                entryCount = analysis.entryCount,
                isVideo = analysis.isVideo,
                outputs = written,
                thumbnailBytes = (analysis.preview?.payload as? OutputPayload.Bytes)?.data,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            onUpdate(current)
            onCompleted(current)
        } catch (e: CancellationException) {
            current = current.copy(
                status = TaskStatus.CANCELLED,
                stage = "已取消",
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            onUpdate(current)
            onCompleted(current)
            throw e
        } catch (e: Throwable) {
            AppLog.e("转换失败: ${task.fileName}", e)
            current = current.copy(
                status = TaskStatus.FAILED,
                progress = 1f,
                stage = "失败",
                errorTitle = ErrorMessages.describe(e),
                errorDetail = e.message,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            onUpdate(current)
            onCompleted(current)
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
