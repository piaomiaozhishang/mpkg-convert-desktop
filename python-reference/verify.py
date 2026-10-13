# -*- coding: utf-8 -*-
"""
verify.py -- prove a decoded image really is the original, using the preview.jpg
that every Wallpaper Engine package ships.

Idea
----
`preview.jpg` is a 1024x1024 square, downscaled crop of the real image (for
5.mpkg: the full 2160 px height, columns ~[1254, 3414]). So if our decode is
correct, that crop -- downscaled to 1024x1024 -- must correlate almost perfectly
with preview.jpg (per-channel normalized cross-correlation > 0.99). If the channel
order or the block layout is wrong, the NCC collapses (< 0.5).

This script:
  1. finds the crop x-offset by a coarse-to-fine search (no OpenCV required);
  2. reports per-channel NCC for the decode as-is, and for R/B swapped.

Usage:
    python verify.py <decoded.png> <preview.jpg>
"""
from __future__ import annotations
import sys
import numpy as np
from PIL import Image

PREVIEW_SIZE = 1024


def ncc(a, b):
    a = np.asarray(a, np.float64); a = a - a.mean()
    b = np.asarray(b, np.float64); b = b - b.mean()
    den = np.sqrt((a * a).sum()) * np.sqrt((b * b).sum()) + 1e-9
    return float((a * b).sum() / den)


def luma(a):
    return a[:, :, 0] * 0.299 + a[:, :, 1] * 0.587 + a[:, :, 2] * 0.114


def find_crop(img: Image.Image, prev: Image.Image):
    W, H = img.size
    # preview is a square crop of the full image (side == H for a full-height crop)
    side = H
    S = 4
    small = np.asarray(img.resize((W // S, H // S), Image.LANCZOS), np.float32)
    ps = np.asarray(prev.resize((256, 256), Image.LANCZOS), np.float32)
    lp = luma(ps)
    s_side = side // S
    best = None
    for sx in range(0, small.shape[1] - s_side + 1):
        reg = luma(small[0:s_side, sx:sx + s_side])
        reg = np.asarray(Image.fromarray(reg.astype(np.float32)).resize((256, 256), Image.BILINEAR), np.float32)
        s = ncc(reg, lp)
        if best is None or s > best[1]:
            best = (sx, s)
    c = best[0] * S
    prev_np = np.asarray(prev, np.float32)
    bestf = None
    for x0 in range(max(0, c - 60), min(W - side, c + 60) + 1, 2):
        reg = np.asarray(img.crop((x0, 0, x0 + side, side)).resize((PREVIEW_SIZE, PREVIEW_SIZE), Image.LANCZOS), np.float32)
        sc = np.mean([ncc(reg[:, :, i], prev_np[:, :, i]) for i in range(3)])
        if bestf is None or sc > bestf[1]:
            bestf = (x0, sc)
    return bestf[0], side, prev_np


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    img = Image.open(argv[1]).convert("RGB")
    prev = Image.open(argv[2]).convert("RGB")
    x0, side, prev_np = find_crop(img, prev)

    reg = np.asarray(img.crop((x0, 0, x0 + side, side)).resize((PREVIEW_SIZE, PREVIEW_SIZE), Image.LANCZOS), np.float32)
    native = [ncc(reg[:, :, i], prev_np[:, :, i]) for i in range(3)]
    luma_n = ncc(luma(reg), luma(prev_np))
    sw = reg[:, :, [2, 1, 0]]
    swapped = [ncc(sw[:, :, i], prev_np[:, :, i]) for i in range(3)]

    print("image = %s   preview = %s" % (img.size, prev.size))
    print("crop  = x[%d, %d] y[0, %d]   (downscaled to %d)" % (x0, x0 + side, side, PREVIEW_SIZE))
    print("NCC  as-decoded  R,G,B = %s   mean=%.4f   luma=%.4f" %
          ([round(v, 4) for v in native], float(np.mean(native)), luma_n))
    print("NCC  R/B swapped R,G,B = %s   mean=%.4f" % ([round(v, 4) for v in swapped], float(np.mean(swapped))))
    ok = float(np.mean(native)) > 0.95 and float(np.mean(native)) > float(np.mean(swapped))
    print("VERDICT: %s" % ("PASS (decode matches preview, channel order correct)" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
