package com.doubao.repkg

import com.mpkg.scene.core.AssetKind
import com.mpkg.scene.core.ByteArraySeekableData
import com.mpkg.scene.core.Extractor
import com.mpkg.scene.core.PkgReader
import com.mpkg.scene.core.SeekableData
import com.mpkg.scene.core.SliceInputStream
import com.mpkg.scene.core.SliceSeekableData
import com.mpkg.scene.core.TexAssetDecoder
import com.mpkg.scene.core.copyStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * W1 regression tests: large packages must be analyzed and exported **without**
 * loading a video payload into the heap.
 *
 * Everything here is pure JVM. The multi-GB cases use [SyntheticPackage], which
 * is a few KB in memory but reports (and addresses) gigabyte-sized entries, so
 * "did the code read 2.5 GB?" is answered by counting bytes instead of by
 * running out of memory.
 */
class StreamExportTest {

    // ---------- tiny binary helpers (in a companion so the nested builders can use them) ----------

    private companion object {
        fun le32(n: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

        /** Little-endian int32 written from a Long (package fields are uint32). */
        fun le32u(v: Long): ByteArray = le32(v.toInt())

        fun i32s(s: ByteArray): ByteArray = le32(s.size) + s
    }

    private fun fakeMp4(marker: String, extra: Int): ByteArray {
        val payload = marker.toByteArray() + ByteArray(extra) { (it % 251).toByte() }
        val body = "ftypisom".toByteArray() + ByteArray(4) + payload
        return ByteBuffer.allocate(4).putInt(8 + body.size).array() + body
    }

    /** Mobile (PKGM) video tex: TEXB0004 with the compact v3 mipmap layout. */
    private fun makeMobileVideoTex(mp4: ByteArray): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(32)
        out += le32(1920); out += le32(1080); out += le32(1920); out += le32(1080)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(1)
        out += le32(1920); out += le32(1080)
        out += le32(0); out += le32(mp4.size); out += le32(mp4.size)
        out += mp4
        return out
    }

    /**
     * The same header, but *declaring* [payloadSize] bytes without carrying them.
     * Lets a test place a 100 MB texture payload in a synthetic package.
     */
    private fun makeMobileVideoTexHeader(payloadSize: Long): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(32)
        out += le32(1920); out += le32(1080); out += le32(1920); out += le32(1080)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(1)
        out += le32(1920); out += le32(1080)
        out += le32(0); out += le32u(payloadSize); out += le32u(payloadSize)
        return out
    }

    // ---------- synthetic, gigabyte-capable package ----------

    /**
     * One entry's data region. Only [prefix] holds real bytes; the rest of the
     * region reads back as zeros, which is all a header/offset test needs.
     */
    private class Region(
        val name: String,
        val start: Long,
        val length: Long,
        val prefix: ByteArray = ByteArray(0)
    )

    /**
     * A [SeekableData] whose size is measured in gigabytes but whose memory
     * footprint is the header plus a few prefixes. Counts every byte it serves,
     * per region, so a test can prove what was and was not read.
     */
    private class SyntheticPackage(
        private val header: ByteArray,
        private val regions: List<Region>
    ) : SeekableData {

        private val total: Long =
            regions.maxOf { it.start + it.length }.coerceAtLeast(header.size.toLong())

        override val size: Long get() = total

        /** Largest single read requested by the code under test. */
        var maxReadLength: Int = 0
            private set

        /** Total bytes served across all reads. */
        var totalServed: Long = 0L
            private set

        private val servedIn = HashMap<String, Long>()

        override fun readAt(offset: Long, length: Int): ByteArray {
            if (offset < 0 || length < 0 || offset + length > total) {
                throw EOFException("synthetic read out of range: $offset+$length > $total")
            }
            if (length == 0) return ByteArray(0)

            maxReadLength = maxOf(maxReadLength, length)
            totalServed += length

            val out = ByteArray(length)

            // Real header bytes.
            copyOverlap(out, offset, header, 0L)

            // Entry regions: counted always, content only within each prefix.
            for (r in regions) {
                val from = maxOf(offset, r.start)
                val to = minOf(offset + length, r.start + r.length)
                if (to <= from) continue
                servedIn[r.name] = (servedIn[r.name] ?: 0L) + (to - from)
                copyOverlap(out, offset, r.prefix, r.start)
            }
            return out
        }

        /** Copies the part of [src] (based at [srcStart]) overlapping the read. */
        private fun copyOverlap(out: ByteArray, readOffset: Long, src: ByteArray, srcStart: Long) {
            val from = maxOf(readOffset, srcStart)
            val to = minOf(readOffset + out.size, srcStart + src.size)
            if (to <= from) return
            System.arraycopy(
                src, (from - srcStart).toInt(),
                out, (from - readOffset).toInt(),
                (to - from).toInt()
            )
        }

        /** Bytes served out of the region called [name]. */
        fun bytesReadIn(name: String): Long = servedIn[name] ?: 0L
    }

    /** Builds a package header declaring arbitrary (even gigabyte) entry sizes. */
    private class PkgBuilder(private val magic: String) {
        private val entries = ArrayList<Triple<String, Long, Long>>()

        fun add(path: String, relOffset: Long, length: Long) = apply {
            entries.add(Triple(path, relOffset, length))
        }

        /** @return header bytes; entry data starts at `header.size` and at each relOffset. */
        fun header(): ByteArray {
            var h = i32s(magic.toByteArray())
            h += le32(entries.size)
            for ((path, relOffset, length) in entries) {
                h += i32s(path.toByteArray())
                h += le32u(relOffset)
                h += le32u(length)
            }
            return h
        }
    }

    // ================= 1. streaming copy correctness =================

    @Test
    fun streamCopyIsByteIdenticalToTheSourceRange() {
        val source = ByteArray(5 * 1024 * 1024 + 137) { ((it * 31 + 7) and 0xFF).toByte() }
        val offset = 4096L + 13
        val length = 3L * 1024 * 1024 + 991
        val expected = source.copyOfRange(offset.toInt(), (offset + length).toInt())

        val out = ByteArrayOutputStream()
        SliceInputStream(ByteArraySeekableData(source), offset, length).use { input ->
            val copied = copyStream(input, out)
            assertEquals("copied byte count", length, copied)
        }
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun streamCopyIsIdenticalForEveryChunkAndBufferSize() {
        val source = ByteArray(300_000) { (it % 253).toByte() }
        val offset = 777L
        val length = 200_000L
        val expected = source.copyOfRange(offset.toInt(), (offset + length).toInt())

        for (chunk in listOf(1, 7, 4096, 65536, Int.MAX_VALUE)) {
            for (buffer in listOf(1, 3, 8192, 200_000)) {
                val out = ByteArrayOutputStream()
                SliceInputStream(
                    ByteArraySeekableData(source), offset, length, chunkSize = chunk
                ).use { input -> copyStream(input, out, bufferSize = buffer) }
                assertArrayEquals(
                    "chunk=$chunk buffer=$buffer", expected, out.toByteArray()
                )
            }
        }
    }

    @Test
    fun sliceStreamNeverReadsOutsideItsOwnRange() {
        val source = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val offset = 10_000L
        val length = 20_000L

        var bytesServed = 0L
        val counted = object : SeekableData {
            override val size: Long get() = source.size.toLong()
            override fun readAt(offset: Long, length: Int): ByteArray {
                bytesServed += length
                return ByteArraySeekableData(source).readAt(offset, length)
            }
        }

        val out = ByteArrayOutputStream()
        SliceInputStream(counted, offset, length).use { input -> copyStream(input, out) }

        assertEquals(length, out.size().toLong())
        // The whole point: only the requested range is pulled from the source.
        assertEquals("bytes read from the package", length, bytesServed)
    }

    @Test
    fun sliceSeekableDataClampsToTheParentSize() {
        val parent = ByteArraySeekableData(ByteArray(1000))
        val slice = SliceSeekableData(parent, 900, 500)
        assertEquals(100, slice.size)
        assertArrayEquals(ByteArray(100), slice.readAt(0, 100))
        try {
            slice.readAt(50, 100)
            throw AssertionError("reading past the slice end must throw")
        } catch (expected: EOFException) {
            // expected
        }
    }

    // ================= 2. video pick uses TOC length only =================

    @Test
    fun picksTheLongerVideoWithoutReadingTheLosersPayload() {
        // Two real video textures; the smaller one must never be decoded.
        val small = makeMobileVideoTex(fakeMp4("SMALL", 40_000))
        val big = makeMobileVideoTex(fakeMp4("BIG", 90_000))

        val builder = PkgBuilder("PKGM0019")
            .add("materials/small.tex", 0, small.size.toLong())
            .add("materials/big.tex", small.size.toLong(), big.size.toLong())
        val header = builder.header()

        val regions = listOf(
            Region("small", header.size.toLong(), small.size.toLong(), small),
            Region(
                "big", header.size.toLong() + small.size, big.size.toLong(), big
            )
        )
        val pkg = SyntheticPackage(header, regions)

        val r = Extractor.analyze(pkg)

        assertTrue("video wallpaper expected", r.isVideo)
        assertNotNull(r.main)
        assertEquals(AssetKind.VIDEO, r.main!!.kind)
        assertEquals("materials/big.tex", r.main!!.sourceEntry)

        // The loser is only ever header-peeked (twice) plus a 12-byte brand probe.
        val loserBytes = pkg.bytesReadIn("small")
        assertTrue(
            "the 40 KB losing video must not be read (read $loserBytes bytes)",
            loserBytes < 512
        )
    }

    @Test
    fun lengthComparisonPrefersTheBiggerEntryEvenWhenTheSmallerIsFirst() {
        val a = makeMobileVideoTex(fakeMp4("AAA", 10_000))
        val b = makeMobileVideoTex(fakeMp4("BBB", 200_000))

        val builder = PkgBuilder("PKGM0019")
            .add("v/a.tex", 0, a.size.toLong())
            .add("v/b.tex", a.size.toLong(), b.size.toLong())
        val header = builder.header()
        val pkg = SyntheticPackage(
            header,
            listOf(
                Region("a", header.size.toLong(), a.size.toLong(), a),
                Region("b", header.size.toLong() + a.size, b.size.toLong(), b)
            )
        )

        val r = Extractor.analyze(pkg)
        assertEquals("v/b.tex", r.main!!.sourceEntry)
    }

    // ================= 3. multi-GB entries are streamed =================

    @Test
    fun multiGigabyteMp4BecomesAStreamSpecInsteadOfBytes() {
        val huge = 2_500_000_000L // 2.5 GB: far past the old ">2GB" and OOM limits
        val mp4Head = fakeMp4("HUGE", 0)

        val small = 1_000_000L
        val builder = PkgBuilder("PKGM0019")
            .add("videos/small.mp4", 0, small)
            .add("videos/huge.mp4", small, huge)
        val header = builder.header()

        val pkg = SyntheticPackage(
            header,
            listOf(
                Region("small", header.size.toLong(), small),
                Region("huge", header.size.toLong() + small, huge, mp4Head)
            )
        )

        val r = Extractor.analyze(pkg)

        assertTrue(r.isVideo)
        val main = r.main
        assertNotNull(main)
        assertEquals("videos/huge.mp4", main!!.sourceEntry)

        // The 2.5 GB payload must be referenced, not buffered.
        assertNull("a 2.5 GB entry must not be materialised", main.bytes)
        val spec = main.streamSource
        assertNotNull("a 2.5 GB entry must carry a stream spec", spec)
        assertEquals(huge, spec!!.length)
        assertTrue(spec.isMp4)
        assertEquals(header.size.toLong() + small, spec.dataOffset)

        // And the analysis itself stayed tiny: only the package header (72 bytes)
        // was ever served — the 1 MB entry was not needed and the 2.5 GB one was
        // never touched.
        assertEquals("the huge payload must not be read at all", 0L, pkg.bytesReadIn("huge"))
        assertEquals("the small entry was not needed either", 0L, pkg.bytesReadIn("small"))
        assertTrue(
            "analysis served ${pkg.totalServed} bytes; expected only the TOC",
            pkg.totalServed < 4096
        )
        assertTrue("largest single read was ${pkg.maxReadLength}", pkg.maxReadLength <= 256)
    }

    @Test
    fun streamSpecAddressesTheMp4InsideALargeVideoTexture() {
        // 100 MB mipmap -> above the streaming threshold, inside a .tex header.
        val payload = 100L * 1024 * 1024
        val mp4Head = fakeMp4("FROMTEX", 0)
        val texHeader = makeMobileVideoTexHeader(payload)

        val builder = PkgBuilder("PKGM0019")
            .add("materials/big.tex", 0, texHeader.size + payload)
        val header = builder.header()

        val entryStart = header.size.toLong()
        // Real header + MP4 brand bytes, then the declared (huge) zero payload.
        val texRegion = Region("tex", entryStart, texHeader.size + payload, texHeader + mp4Head)
        val pkg = SyntheticPackage(header, listOf(texRegion))

        val r = Extractor.analyze(pkg)

        assertTrue("video wallpaper expected", r.isVideo)
        val main = r.main
        assertNotNull(main)
        assertNull("a 100 MB texture payload must not be buffered", main!!.bytes)
        val spec = main.streamSource
        assertNotNull("expected a stream spec for the tex payload", spec)
        assertEquals(payload, spec!!.length)

        // The offset comes from the decoder's own parse, so it must land exactly
        // on the embedded MP4 — verify the brand straight off the package.
        assertEquals(entryStart + texHeader.size, spec.dataOffset)
        val brand = pkg.readAt(spec.dataOffset + 4, 8)
        assertEquals("ftypisom", String(brand, Charsets.US_ASCII))

        // The 100 MB payload itself was never pulled into the analysis.
        assertTrue(
            "analysis served ${pkg.totalServed} bytes for a 100 MB texture",
            pkg.totalServed < 4096
        )
    }

    @Test
    fun smallVideoKeepsItsBytesSoThePreviewCardStillWorks() {
        val mp4 = fakeMp4("SMALLVIDEO", 4_000)
        val tex = makeMobileVideoTex(mp4)
        val builder = PkgBuilder("PKGM0019").add("materials/clip.tex", 0, tex.size.toLong())
        val header = builder.header()
        val pkg = SyntheticPackage(
            header, listOf(Region("tex", header.size.toLong(), tex.size.toLong(), tex))
        )

        val r = Extractor.analyze(pkg)

        val main = r.main
        assertNotNull(main)
        assertNull("small videos stay on the byte path", main!!.streamSource)
        assertArrayEquals("bytes must be exactly the embedded MP4", mp4, main.bytes)
    }

    // ================= 4. streaming export of a real (small) package =================

    @Test
    fun streamingARegionOutOfARealPackageYieldsTheEmbeddedMp4() {
        val mp4 = fakeMp4("REALPKG", 20_000)
        val tex = makeMobileVideoTex(mp4)
        val pkgBytes = assemble("PKGM0019", listOf("materials/clip.tex" to tex))
        val src = ByteArraySeekableData(pkgBytes)

        // Locate the payload the same way the extractor does...
        val archive = PkgReader.read(src)
        val entry = archive.entries.single()
        val span = TexAssetDecoder.locateFirstMipmap(
            SliceSeekableData(src, entry.dataOffset, entry.length), mobileLayout = true
        )
        assertNotNull(span)
        assertTrue(span!!.isVideo)

        // ...then stream it out and compare with the original MP4 byte for byte.
        val out = ByteArrayOutputStream()
        SliceInputStream(src, entry.dataOffset + span.payloadOffset, span.byteCount.toLong())
            .use { input -> copyStream(input, out) }
        assertArrayEquals(mp4, out.toByteArray())
    }

    private fun assemble(magic: String, entries: List<Pair<String, ByteArray>>): ByteArray {
        var header = i32s(magic.toByteArray())
        header += le32(entries.size)
        var data = ByteArray(0)
        var cursor = 0
        for ((p, b) in entries) {
            header += i32s(p.toByteArray())
            header += le32(cursor)
            header += le32(b.size)
            data += b
            cursor += b.size
        }
        return header + data
    }
}
