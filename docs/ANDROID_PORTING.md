# ANDROID_PORTING.md — building the Android converter

The reference Kotlin in `android/` was compiled with **kotlinc 2.0.21 (JRE 17)** and run against
`testdata/`:

```
TestVectors build 2
ETC2_RGBA8 vectors : 60 blocks, mismatches = 0
LZ4 block cases    : 7 cases,  mismatches = 0
end-to-end         : decoded 3840x2160 (8294400 px) in 105 ms
ALL CHECKS PASSED
```

`105 ms` is the **JVM** timing for a full 4K ETC2_RGBA8 decode (105–111 ms across runs). Expect
roughly 2–4× that on a mid-range ARM device under ART (call it ~0.3–0.5 s), which is perfectly
acceptable for a "pick a file → get a PNG" flow. Decode off the main thread anyway.

---

## 1. Two ways to decode — pick one

### Route A — pure-Kotlin CPU decode (simplest, recommended)

Use `android/MpkgExtractor.kt` + `android/Etc2Rgba8Decoder.kt` + `android/Lz4BlockDecoder.kt`.
No native code, no GPU, no third-party dependency, works on any API level.

```kotlin
// on a background thread
val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
when (val r = MpkgExtractor.extract(bytes)) {
    is MpkgExtractor.Result.Decoded -> {
        val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(r.argb, 0, r.width, 0, 0, r.width, r.height)
        // save as PNG
        FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    is MpkgExtractor.Result.EmbeddedImage -> {
        // payload was already a whole PNG/JPEG — just write r.bytes to r.extension
        FileOutputStream(outFile).use { it.write(r.bytes) }
    }
}
```

### Route B — let the GPU do it (zero CPU decode)

`ETC2_RGBA8 == GL_COMPRESSED_RGBA8_ETC2_EAC`, which is **mandatory in OpenGL ES 3.0** (so every
device since ~API 18 / 2013 supports it). You can upload the raw mip bytes straight to a texture:

```kotlin
val tex = IntArray(1); glGenTextures(1, tex, 0)
glBindTexture(GL_TEXTURE_2D, tex[0])
// rawETC2 is the LZ4-decompressed mip 0 payload, width x height
glCompressedTexImage2D(
    GL_TEXTURE_2D, 0, GL_COMPRESSED_RGBA8_ETC2_EAC,
    width, height, 0, rawETC2.size, ByteBuffer.wrap(rawETC2))
```

Then either (a) render it directly in a `GLSurfaceView` — you never need a PNG at all; or
(b) render to an FBO and `glReadPixels` into a Bitmap if you really must export an image.
Route B costs ~0 CPU but is much more plumbing. Use it only if Route A is too slow on the
target device.

> Yes, you could also try `android.opengl.ETC1Util`, but that is **ETC1 only** — it does not
> understand ETC2_RGBA8. Don't go down that path.

---

## 2. Things that will bite you on Android

**Heap.** A 3840×2160 decode needs:
* raw ETC2 mip in memory: `3840*2160 = 8.3 MB`
* output `IntArray`: `3840*2160*4 = 33 MB`
* the `Bitmap`: another `33 MB`
Peak ≈ 75 MB. On low-heap devices, decode straight into the `Bitmap`'s backing array
(`bmp.setPixels`) and free the intermediate array as soon as possible, or enable
`android:largeHeap="true"`. Do **not** keep the ETC2 bytes around after decoding.

**Allocation churn.** `Etc2Rgba8Decoder` deliberately reuses two private scratch arrays
(`rgbScratch`, `alphaScratch`) so the per-block inner loop allocates nothing. That makes it
**not re-entrant** — decode on one thread, or make the scratch arrays `ThreadLocal` if you
parallelise.

**LZ4.** `Lz4BlockDecoder.kt` is complete; no dependency needed. If you prefer a library,
`org.lz4:lz4-java` (`LZ4FastDecompressor.decompress(src, dest)`) implements the same block
format — but note it is the *block* API you want, not the frame API.

**Do not ship `preview.jpg`.** It is a 1024×1024 thumbnail that every package contains; it is
tempting and it is not the original. Always prefer the `.tex` payload.

**Format guard.** Check the `.tex` format byte before decoding:

```kotlin
if (fmt != MpkgExtractor.FORMAT_ETC2_RGBA8) {
    // tell the user "unsupported texture format $fmt" instead of producing garbage
}
```
Value 5 is the one this package implements end-to-end. `0` (RGBA8888), `9` (R8) and the
embedded-PNG/JPEG cases are trivial and already handled; DXT1/3/5 and BC7 (`4/6/7/12`) would
need a BC decoder, and `35` means it is a **video** wallpaper (no still image to extract).

**Channel order.** Decode as RGBA. Do **not** add an R/B swap "because a tutorial said BGR" —
that advice comes from `texture2ddecoder` returning BGRA. Verify against `preview.jpg`
(expect per-channel NCC ≈ 0.998, see `python/verify.py`) or against `testdata/facts.json`.

**Non-multiple-of-4 sizes.** ETC2 works on 4×4 blocks; `ImageWidth/Height` in the `.tex` header
may be smaller than `TextureWidth/Height` (rounded up to a multiple of 4). Crop the decoded
image to `ImageWidth × ImageHeight` if they differ. For `5.mpkg` they are both 3840×2160.

---

## 3. Suggested app flow

1. `ACTION_OPEN_DOCUMENT` (SAF) → let the user pick a `.mpkg` / `.pkg`.
   Wallpaper Engine's own files live under the app's `projects/…/scene.pkg` on desktop, but for
   a converter app the user simply picks the file they have.
2. Parse `.mpkg` → find `*.tex` (or `TEXV0005` magic).
3. Decode → Bitmap.
4. Show a preview + offer "Save PNG" (`MediaStore.Images` on API 29+, or app-specific dir).
5. Show the size/format so the user can tell what they got (e.g. "ETC2_RGBA8 → 3840×2160").

Expected result for `5.mpkg`: **3840×2160**, and the pixels must hash to
`sha256` recorded in `testdata/facts.json` (`output.expected_pixels_sha256_*`), or simply match
`sample/preview.jpg` at NCC ≈ 0.998.
