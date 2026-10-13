# FORMAT_SPEC.md — Wallpaper Engine `.mpkg` / `.tex` / ETC2_RGBA8, bit by bit

Everything here was verified against a real wallpaper (`5.mpkg`, see `testdata/facts.json`)
and cross-checked against RePKG's C# sources for the container layers and against the
Khronos OpenGL ES / Data Format specifications (via `iOrange/etcdec` and Android's DEQP
conformance tests) for the compression layer.

All integers are **little-endian** unless stated otherwise.

---

## 1. `.mpkg` / `.pkg` package container

```
offset 0   : int32  magicLength           (== 8)
offset 4   : bytes  magic                (== "PKGM0019", no NUL)
offset 12  : int32  entryCount
then entryCount times:
             int32  pathLength
             bytes  path                 (UTF-8, no NUL, e.g. "materials/foo.tex")
             int32  offset               (relative to dataStart)
             int32  length
dataStart = position right after the last entry
entry payload = file[dataStart + offset , + length)
```

Notes
* The "magic" is a *length-prefixed* string, not a fixed 8 bytes — but in practice it is `PKGM0019`.
* Do **not** hard-code the TOC size: it depends on the entry path lengths.
* A more format-agnostic fallback is to scan the file for `TEXV0005\0` (works when a package
  holds exactly one texture, which is the common static-wallpaper case).
* The `.tex` entry is usually named after the material, e.g.
  `materials/<name>.tex`. `preview.jpg` is a **thumbnail**, never the original.

---

## 2. `.tex` texture

```
+0   9 bytes "TEXV0005\0"
+9   9 bytes "TEXI0001\0"
+18  7 x int32:
        Format            (see §4; 5 == ETC2_RGBA8)
        Flags             (1=NoInterpolation, 2=ClampUVs, 4=IsGif, 32=IsVideo)
        TextureWidth, TextureHeight
        ImageWidth,   ImageHeight
        UnkInt0
+46  9 bytes container magic "TEXB0001".."TEXB0004" + '\0'
```

### 2.1 Image container

```
+55  int32 imageCount
     TEXB0001/0002 : (nothing extra)
     TEXB0003      : int32 ImageFormat
     TEXB0004      : int32 ImageFormat, int32 isVideoMp4
```

`ImageFormat` is a **FreeImage** format code:

| value | meaning | how to handle |
|---|---|---|
| `13` | PNG  | the mip bytes *are* a complete PNG file — just write them out |
| `2`  | JPEG | same |
| `25` | GIF  | same |
| `35` | MP4  | video wallpaper; not a still image |
| `-1` | RAW  | decode with §4/§5 below |

**Version downgrade rule (important):** if the container is `TEXB0004` but `ImageFormat != MP4`,
the mip records use the *older* `TEXB0003` layout (no extra params). RePKG does exactly this
(`TexImageContainerReader`). Getting this wrong is the usual cause of "mip header looks like garbage".

### 2.2 Mipmaps

```
int32 mipmapCount            (commonly 1)
for each mip:
     [ TEXB0004 + MP4 only: int32 param1(=1), int32 param2(=2),
                            NUL-terminated json string, int32 param3(=1) ]
     int32 width
     int32 height
     int32 isLz4Compressed     (1 == LZ4 block compressed)
     int32 decompressedSize    (bytes after decompression)
     int32 compressedSize
     bytes[compressedSize]  payload
```

Then, if the texture is animated, an optional frame-info container follows (`TEXF...`);
a static wallpaper has none.

Payload handling:
1. if `isLz4Compressed == 1` → `lz4_block_decompress(payload, decompressedSize)` (see §6).
2. sanity check `len == width * height * bytesPerPixel(format)` inside the first mip.

For `5.mpkg` the first mip payload is LZ4 and expands to exactly `3840*2160*1 = 8 294 400` bytes.

---

## 3. Why format 5 is "the hard part"

The `Format` field of a Wallpaper Engine `.tex` is a `TexFormat` enum. The values that are
documented in RePKG / linux-wallpaperengine / various reverse-engineering notes are:

```
0 RGBA8888   1 RGB888   2 RGB565   3 ETC1_RGB8   4 DXT5/BC3
6 DXT3/BC2   7 DXT1/BC1   8 RG88   9 R8   12 BC7
```

**Value 5 is missing from all of them.** RePKG's `TexFormat` enum is literally
`{ RGBA8888=0, DXT5=4, DXT3=6, DXT1=7, RG88=8, R8=9 }`, so RePKG throws
`EnumNotValidException` and refuses to open such a file.

`Format == 5` means **ETC2_RGBA8** (`GL_COMPRESSED_RGBA8_ETC2_EAC`).

Provenance: the shader header `#define FORMAT_ETC2_RGBA8 5` in
[`yakisoba0728/Waple`](https://github.com/yakisoba0728/Waple) (`scripts/spec/check_tex_format_map.py`).
It is also consistent with the value's 1 byte/pixel footprint and the block fingerprint below.

---

## 4. Format 5 = ETC2_RGBA8

### 4.1 Block layout

A 4×4 block is **16 bytes**:

```
bytes [0 .. 7]   = 64-bit EAC alpha block
bytes [8 .. 15]  = 64-bit ETC2 RGB block
```

Both halves are read as **big-endian 64-bit integers** (so byte 0 is the MSB of the alpha word).

This ordering (alpha first) is what the Khronos data-format spec states for
`GL_COMPRESSED_RGBA8_ETC2_EAC` and what Android's DEQP uses
(`compressedBlockAlpha = get128BitBlockStart(src)`, `compressedBlockRGB = get128BitBlockEnd(src)`).

**Opaque fingerprint.** A fully-opaque block has the alpha word exactly
`FF 00 00 00 00 00 00 00` (base = 255, multiplier = 0, all indices = 0). If *every* block in a
file starts with those 8 bytes, you are looking at an ETC2_RGBA8 texture with a fully opaque
alpha channel — that single observation resolves format 5 in one glance.

### 4.2 The ETC2 RGB half (bit positions are MSB-first, bit 63 = most significant)

`flip` is bit 32, `diff` is bit 33.

**Individual mode (`diff == 0`)** — two independent RGB444 base colors:

| field | bit range | meaning |
|---|---|---|
| `R1` | 63..60 | red base of subblock 1 |
| `R2` | 59..56 | red base of subblock 2 |
| `G1` | 55..52 | green base 1 |
| `G2` | 51..48 | green base 2 |
| `B1` | 47..44 | blue base 1 |
| `B2` | 43..40 | blue base 2 |
| `cw1` | 39..37 | modifier-table selector, subblock 1 |
| `cw2` | 36..34 | modifier-table selector, subblock 2 |
| indices | 31..0 | 16 × 2-bit pixel selectors (see below) |

4→8 bit expansion is **bit replication**: `v8 = (v4 << 4) | v4`.

**Differential mode (`diff == 1`)** — one RGB555 base plus a signed 3-bit delta:

| field | bit range | meaning |
|---|---|---|
| `R` | 63..59 | red base, subblock 1 |
| `dR` | 58..56 | sign-extended delta → subblock 2 |
| `G` | 55..51 | green base 1 |
| `dG` | 50..48 | green delta |
| `B` | 47..43 | blue base 1 |
| `dB` | 42..40 | blue delta |

5→8 bit expansion: `v8 = (v5 << 3) | (v5 >> 2)`.

`R2 = R + dR` etc. **If any sum falls outside 0..31, the block is not differential at all** — it is
one of the three ETC2-only modes below (checked in this order: R → T mode, G → H mode, B → planar).

**T mode (R overflows), bits:**

| field | bits | | field | bits |
|---|---|---|---|---|
| `R0[3:2]` | 60..59 | | `d` low bit | 32 |
| `R0[1:0]` | 57..56 | | `G0` | 55..52 |
| `B0` | 51..48 | | `R1` | 47..44 |
| `G1` | 43..40 | | `B1` | 39..36 |
| `d` high bits | 35..34 | | | |

Base color 1 (+/− distance) is used for two of the four palette entries, base color 0 for one.

**H mode (G overflows), bits:** `R0` 62..59, `G0a` 58..56, `G0b` 52, `B0a` 51, `B0b` 49..47,
`R1` 46..43, `G1` 42..39, `B1` 38..35, `d` low 34, `d` high 32. The palette is
`base0 ± d, base1 ± d`, where `d` is selected by `(dh<<2)|(dl<<1)|(base0 >= base1)`.

**Planar mode (B overflows), bits:** `RO` 62..57 (+bit 56), `GO` 54..49 (+bit 56/48),
`BO` 44..39, `RH` 38..34, `GH` 31..25, `BH` 24..19, `RV` 18..13, `GV` 12..6, `BV` 5..0.
Two edge colors + one origin color give a bilinear ramp:
`C(x,y) = x*(CH-CO)/4 + y*(CV-CO)/4 + CO` (x = column, y = row, fixed-point with +2, >>2).

**Distance (modifier) table** for T/H and the **RGB modifier tables** for individual/differential
are reproduced verbatim in `python/pure_etc2.py` (`_ETC_MOD`, `_ETC_DIST`).

**Pixel selector bits.** The 16 2-bit selectors are *not* stored contiguously. For the pixel at
`(row, col)` (row 0 = top, col 0 = left) with `m = row + col*4`:

```
selector = ((word >> (m + 16)) & 1) << 1 | ((word >> m) & 1)
```

i.e. the low bits of the 16 selectors live in bits 0..15 and the high bits in bits 16..31.

**Subblock split** (`flip` = bit 32):
* `flip == 0` → two **2-wide × 4-tall** subblocks, side by side (left / right)
* `flip == 1` → two **4-wide × 2-tall** subblocks, stacked (top / bottom)

so `subblock = (flip == 0) ? (col >> 1) : (row >> 1)`.

### 4.3 The EAC alpha half (8-bit alpha, uint)

| field | bits | meaning |
|---|---|---|
| `base` | 63..56 | 8-bit base alpha |
| `mult` | 55..52 | 4-bit multiplier |
| table | 51..48 | selects one of 16 modifier tables |
| indices | 47..0 | sixteen 3-bit selectors |

```
alpha(row, col) = clamp255(base + modifier_table[table][idx] * mult)
idx             = (word >> (3 * (15 - (col*4 + row)))) & 7
```

The 16×8 modifier table is in `python/pure_etc2.py` (`_EAC_MOD`).

> Note the index order is **column-major**: `k = 15 - (col*4 + row)`, i.e. the first pixel
> (row 0, col 0) reads the high-order 3-bit slot. This trips up many hand-rolled decoders.

---

## 5. Channel order

A correct ETC2_RGBA8 decode is **RGBA**, and it already matches the shipped `preview.jpg`
(per-channel NCC ≈ 0.998) with **no swapping**.

Beware: the popular Python package `texture2ddecoder` returns **BGRA** from `decode_etc2a8`.
If you validate against it you will conclude — wrongly — that the data is "BGR" and insert a
needless R/B swap. Do not; verify against `preview.jpg` instead (`python/verify.py`).

---

## 6. LZ4 block format (the `.tex` mip compression)

Wallpaper Engine writes a *raw LZ4 block* — no frame header, no magic, no checksum; the mip
header supplies the exact uncompressed size.

```
sequence := token literals [offset matchLength]
token    := (literalLength << 4) | (matchLength - 4)      each nibble saturates at 15
ext      := if a nibble == 15, keep adding the following 0xFF bytes (255 each)
            until a byte < 0xFF terminates the run
offset   := uint16 little-endian back-reference distance (1..65535)
match    := copy matchLength bytes starting at (outputPos - offset); may overlap
```

The last sequence of a block contains literals only (no match). Reference implementations:
`python/lz4_block.py`, `android/Lz4BlockDecoder.kt`.

---

## 7. Reference implementations in this package

| file | what it is | verified how |
|---|---|---|
| `python/pure_etc2.py` | dependency-free ETC1/ETC2/EAC decoder | bit-exact vs `texture2ddecoder` on all 518 400 blocks of the real 4K image, and on 20 000 random blocks (0 mismatches modulo that library's BGRA order) |
| `python/lz4_block.py` | dependency-free LZ4 block decoder | byte-exact vs `python-lz4` on the real mip + 20 000 fuzz cases |
| `python/extract_original.py` | end-to-end `.mpkg` → PNG, zero third-party deps | output is **pixel-identical** to the known-good PNG (0 / 24 883 200 values differ) |
| `android/Etc2Rgba8Decoder.kt` | Kotlin port of the ETC2 decoder | compiled with kotlinc 2.0.21 and run against `testdata/selftest_*` |
| `android/Lz4BlockDecoder.kt` | Kotlin LZ4 block decoder | idem, against `testdata/lz4_cases.txt` |
| `android/MpkgExtractor.kt` | Kotlin container parser + integration | idem, end-to-end on `sample/texture.tex` |
