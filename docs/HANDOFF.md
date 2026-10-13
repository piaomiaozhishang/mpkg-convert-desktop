# HANDOFF.md — Wallpaper Engine static wallpaper → original image

**Audience:** the agent building the Android converter (or anyone who has to do this again).
**Status:** solved, verified end-to-end, ported to Kotlin and self-tested.

---

## TL;DR

* A Wallpaper Engine static wallpaper (`5.mpkg`) is a `PKGM0019` package containing a `.tex` whose
  pixel payload is **ETC2_RGBA8**. In the `.tex` header this is **`Format == 5`** — a value that
  appears in **no** public documentation (RePKG, linux-wallpaperengine, and every RE write-up all
  skip it; RePKG's enum doesn't even contain it and refuses to open the file).
* The image is **3840×2160**, stored as a single LZ4-compressed ETC2_RGBA8 mip of exactly
  `3840*2160*1 = 8 294 400` bytes.
* Decode it as ETC2_RGBA8 → **RGBA, no channel swap** → matches the package's own `preview.jpg` at
  per-channel **NCC 0.998**. The result is in `output/壁纸转原图_3840x2160.png` upstream of this
  folder, and reproduced by everything in this package.
* One-line tell for next time: if every 16-byte block starts with `FF 00 00 00 00 00 00 00` and the
  header format byte is `5`, it is ETC2_RGBA8 with opaque alpha. Done.

---

## 1. What's in this package

```
HANDOFF.md            <- you are here: the story, the results, the traps
FORMAT_SPEC.md        <- bit-exact spec of .mpkg / .tex / ETC2_RGBA8 / EAC / LZ4
ANDROID_PORTING.md    <- how to build the Android converter (CPU vs GPU, leaks, perf)
README.md             <- 60-second orientation
requirements.txt      <- ONLY for the optional Python tooling; core needs nothing

python/               zero-third-party-dependency reference implementation
  pure_etc2.py          ETC1/ETC2/EAC/ETC2_RGBA8 decoder (the important file)
  lz4_block.py          LZ4 block decompressor
  minipng.py            tiny stdlib PNG writer
  extract_original.py   .mpkg -> PNG, end to end
  verify.py             proves a decode by NCC-matching preview.jpg

android/              Kotlin port (compiled + self-tested with kotlinc 2.0.21 / JRE 17)
  Etc2Rgba8Decoder.kt   ETC2_RGBA8 -> ARGB IntArray
  Lz4BlockDecoder.kt    LZ4 block format
  MpkgExtractor.kt      container parsing + glue (no Android imports, JVM-testable)
  TestVectors.kt        the self-test harness

testdata/             ground truth for validating any port
  selftest_blocks.bin       60 ETC2_RGBA8 blocks covering every mode
  selftest_expected.bin     expected 16x RGBA per block (64 B each)
  selftest_expected.json    same, labelled + hex, human-readable
  lz4_cases.txt             LZ4 block cases (compressed hex / expected hex)
  facts.json                every parsed header value of the source + hashes
  SHA256SUMS.txt            checksums of the files involved

sample/               real data for an end-to-end test
  texture.tex               the .tex extracted from 5.mpkg (2 403 746 B)
  preview.jpg               the shipped 1024x1024 thumbnail (448 223 B)
```

---

## 2. The source, under the microscope

From `testdata/facts.json` (all values machine-read from the real file):

| item | value |
|---|---|
| source | `D:\视频制作\5.mpkg`, 2 862 443 B, sha256 `928a12c5…f55faf9` |
| package magic | `PKGM0019`, **12** entries |
| texture entry | `materials/【哲风壁纸】二次元-动漫女孩_upscayl_3840px_digital-art-4x.tex` (2 403 746 B) |
| tex magics | `TEXV0005` + `TEXI0001` |
| **tex format** | **`5` = ETC2_RGBA8** |
| flags | `2` (ClampUVs) |
| texture / image size | 3840×2160 / 3840×2160 |
| container | `TEXB0004`, imageCount 1, imageFormat `-1` (RAW), isVideo 0 |
| mipmapCount | 1 |
| mip 0 | 3840×2160, `isLZ4 = 1`, `decompressedSize = 8 294 400`, `compressedSize = 2 403 655` |
| payload offset | `0x5B` (91) |
| LZ4 → | exactly `3840*2160*1` bytes ⇒ **1 byte/pixel** ⇒ a 4 bpp block format |
| output | 3840×2160 RGB; pixel sha256 `b5d2736c…cbfbc75dea` |

The material name (`…_upscayl_3840px_digital-art-4x`) tells you the artwork was AI-upscaled to
3840 px and then ETC2-compressed by Wallpaper Engine — hence the 1 B/px size and the glossy
planar-heavy block mix (see below).

---

## 3. How it was cracked (so nobody repeats the detours)

1. **Found the texture.** `.mpkg` is a length-prefixed-string container; the TOC parse is easy once
   you read RePKG's `PackageReader` (magic + i32 count + [path, offset, length]…). The `.tex` was
   then located unambiguously.
2. **Ruled out the obvious formats.** The header said `Format = 5`, but the enum tables only listed
   `0,1,2,3,4,6,7,8,9,12`. So we brute-forced **DXT1/DXT3/DXT5, BC4/BC5/BC6/BC7, R8, RG88, RGB565,
   both endiannesses, and every channel permutation.** All of them produced "psychedelic rainbow"
   output. Lesson: *rainbow garbage + a per-16-byte-block constant means the block layout is
   different, not that you picked the wrong DXT flavour.*
3. **Found the fingerprint.** Every 16-byte block began with `FF 00 00 00 00 00 00 00`. That is a
   perfectly valid **fully-opaque EAC alpha block** (base 255, multiplier 0, all indices 0). An
   all-blocks constant at offset 0 of a 16-byte block + `1 B/px` + an undocumented format value is
   the signature of **ETC2_RGBA8** (`GL_COMPRESSED_RGBA8_ETC2_EAC`: 8 B alpha + 8 B RGB).
4. **Confirmed the value.** `#define FORMAT_ETC2_RGBA8 5` in `yakisoba0728/Waple` — the only public
   place the number appears. The Khronos Data Format spec and Android's DEQP confirm the block
   layout (alpha half first, big-endian 64-bit words).
5. **Decoded and verified.** ETC2_RGBA8 decode, then match against `preview.jpg`. We also discovered
   a subtlety (see next) that has misled people into a wrong "BGR" conclusion.
6. **Why it looked like a red herring:** the image is mostly **ETC2 planar** mode
   (376 177 planar / 36 004 H / 17 972 T / ~6 455 individual+differential blocks out of 518 400).
   Planar output is a smooth ramp, which is why partial/wrong decodes looked "almost plausible" and
   slowed down the diagnosis.

### The BGR trap (important)

The first decode used the pip `texture2ddecoder` package and needed `rgba[:, :, [2,1,0]]` to match
the preview — which makes it look like the data is stored BGR. **It is not.** `texture2ddecoder`
returns **BGRA** from `decode_etc2a8`; the swap was undoing *its* quirk, not a property of the file.
A correct RGBA decoder needs **no swap** — proven in this package:

```
NCC  as-decoded  R,G,B = [0.9984, 0.9983, 0.9962]   mean=0.9977   luma=0.9988
NCC  R/B swapped R,G,B = [0.3514, 0.9983, 0.3559]   mean=0.5685
```

If you carry the "BGR" folklore into the Android app you will ship a red→blue-swapped wallpaper.

### The alignment trap

`preview.jpg` is a **downscaled** 1024×1024 square crop of the full image — for this file,
columns **x ∈ [1254, 3414]**, rows 0..2160, scaled to 1024. Compare like with like: crop the
2160×2160 region **and then downscale to 1024** before correlating. Comparing a native-resolution
1024×1024 crop against the preview gives a useless NCC ≈ 0.3 and sends you hunting for a
non-existent bug.

---

## 4. The pipeline

```
.mpkG/.pkg bytes
  └─ parse TOC: magic(i32-len) → i32 count → [i32-len path, i32 off, i32 len] → data at dataStart
       └─ pick the entry whose payload starts with "TEXV0005\0"
.tex
  └─ "TEXV0005\0"+"TEXI0001\0", 7×i32 header (Format=5, Flags, TexW/H, ImgW/H, Unk)
       └─ container "TEXB0004\0" + imageCount + ImageFormat(-1) + isVideo(0)
            └─ mipmapCount=1 → mip: w,h,isLZ4,decompSize,compSize,payload
                 └─ LZ4 block decompress → 8 294 400 B
                      └─ ETC2_RGBA8 decode (16 B/block = 8 B EAC alpha + 8 B ETC2 RGB, big-endian)
                           └─ RGBA 3840×2160 → PNG
```

Two container gotchas, both encoded in `python/extract_original.py` and `android/MpkgExtractor.kt`:

* **`TEXB0004` still images use the `TEXB0003` mip layout.** The extra `param1/param2/json/param3`
  fields only exist for MP4 (video) payloads. RePKG does the same downgrade
  (`if version4 && imageFormat != MP4 → version3`). If you blindly apply the v4 layout, the mip
  header parses as garbage.
* Prefer locating the `.tex` by its `TEXV0005\0` magic (robust for the single-texture static
  wallpapers); the TOC is only needed to pick among several entries.

---

## 5. Proof that it's right

| check | result |
|---|---|
| `pure_etc2.py` vs `texture2ddecoder.decode_etc2a8`, all 518 400 blocks of the real image | 0 mismatches (after permuting that library's BGRA order) |
| `pure_etc2.py` vs oracle, 20 000 random 16-byte blocks (fuzz) | 0 mismatches |
| `lz4_block.py` vs `python-lz4` on the real mip | byte-exact |
| `lz4_block.py` vs `python-lz4`, 20 000 fuzz cases | 0 failures |
| `extract_original.py` output vs the known-good PNG | **0 / 24 883 200** pixel values differ |
| decoded image vs `preview.jpg` (per-channel NCC) | **0.998** (swapped: 0.35) |
| Kotlin port vs `testdata/` (kotlinc 2.0.21) | 60/60 blocks, 7/7 LZ4 cases, 0 mismatches |
| Kotlin port end-to-end (JVM) | 3840×2160 decoded in 105 ms |

---

## 6. Consolidated traps

1. **Format 5 is undocumented.** Don't trust any enum table that lacks it; the file will just be
   "invalid" to most parsers. `5 = ETC2_RGBA8`.
2. **Rainbow output ≠ wrong DXT flavour.** Check for a constant per-block prefix first.
3. **Alpha half comes first**, and both halves are **big-endian** 64-bit words.
4. **ETC pixel-selector bits are interleaved:** low bits in word bits 0..15, high bits in 16..31;
   index for pixel `(row,col)` is at `m = row + col*4`.
5. **EAC alpha index order is column-major:** `k = 15 - (col*4 + row)`.
6. **Differential mode can "overflow" into T/H/planar mode.** Decide the mode *after* computing the
   deltas — `R` out of range ⇒ T, else `G` ⇒ H, else `B` ⇒ planar, else differential.
7. **`preview.jpg` is a downscaled crop.** Downscale before comparing; never deliver it as the
   original.
8. **`texture2ddecoder` returns BGRA.** No R/B swap is needed when you decode properly.
9. **TEXB0004 non-video = TEXB0003 mip layout.**
10. **1 B/px sanity check:** `decompressedSize == width*height` for ETC2; if a decode "needs" 2 B/px
    you have the wrong format.

---

## 7. Not covered (know the edges)

* **DXT1/3/5, BC7** (`Format` 4/6/7/12): the container code paths are identical, but you need a BC
  decoder. `texture2ddecoder` provides `decode_bc1/bc3/bc5/bc7` if you want them.
* **Video wallpapers** (`imageFormat == 35` / MP4): no still image to extract; also the only case
  where the TEXB0004 mip really has the extra params.
* **Multi-image / animated `.tex`**: a `TEXF…` frame-info container follows the mip. Static
  wallpapers have none.
* **`RGB565`/`RGB888`/`ETC1`** appear in the enum but not in this file.

---

## 8. If you're picking this up on Android

`ANDROID_PORTING.md` has the detail. Short version:

1. Copy `android/*.kt` into the app; they have no Android imports in the decode path.
2. Decode on a background thread into `Bitmap.Config.ARGB_8888` via `setPixels`.
3. Keep peak memory in mind (~75 MB for a 4K decode) and gate on `Format == 5`.
4. Validate your build against `testdata/` **and** against `sample/preview.jpg` (NCC ≈ 0.998)
   before shipping.
5. Don't add the R/B swap. Really.
