# CROSS_CHECK.md — two independent methods, same pixels

A **second, independent** reverse-engineering effort (the `wallpaper-mpkg提取经验包` produced by
another agent) solved the same `.mpkg → 原图` problem from scratch. The two methods were run
side by side on **the same `.tex`** to see whether they agree. They do — bit for bit.

This document is the evidence, plus the one conceptual trap both sides nearly fell into.

---

## 1. The file

```
sample/texture.tex   sha256 = 991365999029b390e6b955f4e1695984e9aa4b578d213e07a3e5dd70489ef729
```
(The other package extracted the identical file; both sha256 match.)

Parsed header: `Format=5`, `Flags=2`, `Tex=3840×2160`, `Img=3840×2160`, `ImageFormat=-1` (raw),
`isVideoMp4=0`, `mipCount=1`, `mip0=3840×2160`, `isLZ4=1`, `decompressedSize=8294400`,
`compressedSize=2403655`.

## 2. The two decode paths

| | this package (Route A) | other package |
|---|---|---|
| decode call | own `pure_etc2.decode_etc2_rgba8()` | `texture2ddecoder.decode_etc2a8()` |
| channel handling | **no swap** (native RGBA) | **swap R/B** (library returns BGRA) |
| deps | zero | `texture2ddecoder` (C extension) |

## 3. Result — pixel-for-pixel

| variant | differing pixels vs the reference image | RGB pixel sha256 |
|---|---|---|
| A native (`pure_etc2`, no swap) | **0 / 8,294,400** | `b5d2736c…cbfbc75dea` |
| library raw (no swap) | 7,919,837 / 8,294,400 | `a095a7c6…` |
| library + R/B swap | **0 / 8,294,400** | `b5d2736c…cbfbc75dea` |
| reference PNG (`原神-芙宁娜_4K_3840x2160_原图.png`) | — | `b5d2736c…cbfbc75dea` |

**A == B(swapped) == the reference image.** The two methods are equivalent; and the reference
image that the other agent produced is byte-identical (RGB pixels) to this package's known-good
output.

## 4. The trap: "ETC2 decodes to BGRA"

Both efforts first produced *looks-wrong* colors and both reached for an R/B swap. The correct
statement is subtler, and worth getting right:

* **ETC2_RGBA8 as a format decodes to RGBA.** No swap is intrinsic.
* `texture2ddecoder.decode_etc2a8` **returns BGRA** — that library's quirk. Swap is needed
  *only because of that library*.

So: the other package's **code is correct**, but its **written mental model is wrong**. If a later
reader believes "ETC2 is BGRA" and hand-writes or GPU-decodes, they will invert the image.
The GPU route in this package (`android/gles/Etc2Gles.kt`) therefore ships with the **no-swizzle**
shader by default.

## 5. Validation methodology — a real difference

| | this package | other package |
|---|---|---|
| ground truth used | pixel sha256 + test vectors + oracle block-compare | `preview.jpg` average color / MAD |
| channel-order check | **per-channel** NCC (0.9977 correct vs 0.5685 swapped) | average-color distance |
| reproducibility | `testdata/` + `verification` log included | asserted in prose, no artifacts |

Two lessons proven by numbers:

* **Grayscale/luma NCC cannot decide channel order.** Measured here: luma NCC is 0.9988 when
  correct and still 0.9655 when R/B are swapped — nearly indistinguishable. Use a **per-channel**
  or pixel-level test. (`python/verify.py` is per-channel.)
* **`preview.jpg` is a crop — align before comparing.** Compare the *cropped* region
  (`x[1254,3414]`, full height) downscaled to 1024², not the whole 16:9 frame squashed into the
  preview's box. Misalignment inflates any distance metric and can mask a wrong answer.

## 6. Takeaway

Two independent agents converged on the same format (`5 = ETC2_RGBA8`), the same block layout
(`[EAC alpha][ETC2 RGB]`, big-endian halves), the same opaque fingerprint
(`FF 00 00 00 00 00 00 00`), the same LZ4-block container, and finally the same 3840×2160 pixels.
That agreement is strong evidence the format model in `FORMAT_SPEC.md` is right.

What each side contributes to the other:
* this package → provable correctness (hashes, test vectors, zero-dep decoder), and the correct
  channel-order model;
* other package → a ready-made GLES 3.0 fast path (`android/gles/Etc2Gles.kt`) and a broader
  format-compat sweep (`COMPAT_FORMATS.md`).

---

## 7. Erratum: the GPU path's V-flip (found downstream, fixed in v3)

The GLES route in `android/gles/Etc2Gles.kt` was first shipped with a **V flip in the vertex
shader** (`vUv = vec2(x, 0.5 - aPos.y * 0.5)`), inherited verbatim from the other package, whose
comment justified it as "compensating glReadPixels' bottom-left origin". That reasoning is wrong
for read-back:

* `glReadPixels` returns rows starting at the window's **bottom-left**, i.e. bottom-up;
* `Bitmap` row 0 is the **top**;
* the two already cancel out, so a plain UV mapping (`vUv.y = aPos.y * 0.5 + 0.5`) gives an
  upright bitmap — and the extra flip makes it **upside down**.

A real Android app built from this package hit exactly that bug (`Mpkg2Mp4`, "导出的原图颠倒"),
which is how it was caught. `Etc2Gles.kt` now carries **no flip** and an explicit ORIENTATION
warning. Worked example (image 2 rows, top = RED, bottom = BLUE, exported to a 1×2 bitmap):

| shader | FBO bottom row | read-back row 0 | Bitmap top | result |
|---|---|---|---|---|
| flipped (v1, wrong) | BLUE (image bottom) | BLUE | BLUE | **upside down** |
| plain (correct) | RED (image top) | RED | RED | upright |

Rule of thumb: **a V flip is only needed when the texture is drawn on screen, never when it is
read back into an image.** For a CPU decoder, rows are already stored top-first, so no flip is
needed there either.

