package com.mpkg.app.extract

import com.doubao.repkg.AndroidLz4
import com.doubao.repkg.core.Etc2CpuDecoder
import com.doubao.repkg.core.Lz4BlockDecompressor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * core 解码器的桌面端调用封装。
 *
 * 两个要点：
 * 1. LZ4 是**裸 block**（无帧头），由 core 的 `Lz4Java.kt`（原 `AndroidLz4.kt`）实现，纯 JVM 可直接用；
 * 2. `Etc2CpuDecoder` 内部使用共享 scratch 数组，源码明确「NOT re-entrant; decode on a single thread」，
 *    因此这里用可重入锁串行化 ETC2 解码，保证并发解码（T2.4 默认并发 2）也安全。
 */
object CoreDecoding {

    /** core 唯一三方依赖（org.lz4:lz4-java）的适配器。 */
    val lz4: Lz4BlockDecompressor = AndroidLz4

    private val etc2Lock = ReentrantLock()

    /** ETC2_RGBA8 块流 → ARGB 像素。非可重入，内部串行化。 */
    fun decodeEtc2(blocks: ByteArray, width: Int, height: Int): IntArray =
        etc2Lock.withLock { Etc2CpuDecoder.decodeArgb(blocks, width, height) }
}
