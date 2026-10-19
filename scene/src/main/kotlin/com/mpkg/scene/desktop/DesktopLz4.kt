package com.mpkg.scene.desktop

import com.mpkg.scene.core.FormatException
import com.mpkg.scene.core.Lz4BlockDecompressor
import net.jpountz.lz4.LZ4Factory

object DesktopLz4 : Lz4BlockDecompressor {
    // Force the pure Java implementation: no JNI extraction or external DLL.
    private val decoder by lazy { LZ4Factory.safeInstance().fastDecompressor() }
    override fun decompress(source: ByteArray, knownDecompressedLength: Int): ByteArray {
        require(knownDecompressedLength >= 0)
        val target = ByteArray(knownDecompressedLength)
        val consumed = decoder.decompress(source, 0, target, 0, target.size)
        if (consumed != source.size) throw FormatException("LZ4 block size mismatch")
        return target
    }
}
