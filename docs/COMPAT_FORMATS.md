# COMPAT_FORMATS.md — other `Format` values, and how to find/read them

`Format == 5` (ETC2_RGBA8) is the one implemented end-to-end in this package. Real-world
wallpaper collections contain other formats too, so this doc says what each one is, whether
Android can do it, and — if you hit an *unknown* format — how to identify it fast.

> Fused from a second, independent experience package (see `CROSS_CHECK.md`). Its author
> brute-forced an unknown texture across every decoder a library offered and picked the one
> whose output matched the shipped `preview.jpg`. That "sweep the candidates, score against the
> preview" loop is worth keeping in your toolbox.

---

## 1. `Format` enum (Wallpaper Engine `TexFormat`)

| Format | name | bits/px | block | Android feasibility |
|---|---|---|---|---|
| 0  | RGBA8888 | 32 | raw | trivial — `Bitmap` / `IntArray` |
| 1  | RGB888   | 24 | raw | trivial |
| 2  | RGB565   | 16 | raw | trivial |
| 3  | ETC1_RGB8 | 4 | 8B/4×4 | GLES2+, `ETC1Util` or `GL_ETC1_RGB8_OES` (no alpha) |
| 4  | DXT5 / BC3 | 8 | 16B/4×4 | **no GLES support** → software decoder / JNI |
| **5** | **ETC2_RGBA8** | **8** | **16B/4×4** | **GLES3.0 hardware (this package); also pure CPU (`Etc2Rgba8Decoder.kt`)** |
| 6  | DXT3 / BC2 | 8 | 16B/4×4 | **no GLES** → software |
| 7  | DXT1 / BC1 | 4 | 8B/4×4 | **no GLES** → software |
| 8  | RG88 | 16 | raw | trivial (2 channels) |
| 9  | R8   | 8  | raw | trivial (1 channel) |
| 12 | BC7  | 8 | 16B/4×4 | **no GLES** → software / JNI (BC7 is complex) |

Notes
* `ImageWidth/Height` may be < `TextureWidth/Height` (rounded up to a multiple of 4); crop to
  `ImageWidth × ImageHeight` if they differ.
* Values `4/6/7/12` are the S3TC/BPTC family; **OpenGL ES has no S3TC/BPTC**, so the GPU route
  that works so well for ETC2 does not exist for them. Either port a software decoder or ship
  the file to a server / desktop decode step.

## 2. `ImageFormat` (FreeImage codes) — the easy cases

When `ImageFormat` is a real image code, **the mip bytes are a whole image file**; just write them
out (`BitmapFactory.decodeByteArray` on Android):

| value | meaning |
|---|---|
| `13` | PNG |
| `2`  | JPEG |
| `25` | GIF |
| `35` | MP4 (video wallpaper — no still image) |
| `-1` | RAW — decode per `FORMAT_SPEC.md` §4/§5 |

## 3. First thing to try in a converter app

Order by (coverage ÷ effort):

1. **`ImageFormat` in {PNG, JPEG}** → one `BitmapFactory.decodeByteArray`. Done.
2. **`Format == 0`** → `Bitmap.createBitmap(...ARGB_8888)` straight from the bytes.
3. **`Format == 5`** → Route A (CPU, verified) or Route B (`android/gles/Etc2Gles.kt`).
4. Anything else → show *"unsupported texture format N"* rather than emitting garbage. Log the
   value and the source file so the map above can be extended.

## 4. Methodology: identifying an unknown `Format`

If you meet a format you can't name, do what the other package did — score candidates against
`preview.jpg`:

1. Dump the LZ4-decompressed mip to a raw file.
2. Run every decoder you have (`BC1/3/4/5/6/7`, ASTC, ETC1, ETC2, ETC2A1, ETC2A8, raw RGBA/RGB)
   over it.
3. For each, compute a distance to `preview.jpg` and keep the winner.
4. Confirm channel order separately (see `CROSS_CHECK.md` — a per-channel or pixel-level check;
   a grayscale/luma check is *not* reliable for this).

Caveat learned the hard way: **align before you compare.** `preview.jpg` is a *square crop*,
not the full frame — resize the *cropped region* to 1024², don't squash the whole 16:9 image into
the same box. Otherwise your distance is dominated by misalignment, not by correctness
(this package's `python/verify.py` does the crop correctly; the other package's scratch scripts
did not, which is why its reported MADs looked large).
