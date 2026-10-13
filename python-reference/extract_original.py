# -*- coding: utf-8 -*-
"""
extract_original.py -- Wallpaper Engine `.mpkg` / `.pkg` / `.tex` -> original PNG.

ZERO third-party dependencies: uses only the standard library plus the two helper
modules that ship next to this file (`pure_etc2.py`, `lz4_block.py`, `minipng.py`).
(That means it runs on a bare CPython -- no numpy / Pillow / lz4 / texture2ddecoder.)

Usage:
    python extract_original.py <wallpaper.mpkg|.pkg|.tex> [output.png]

What it does
------------
1. `.mpkg`/.pkg : parse the package TOC, pick the entry whose payload is a `.tex`.
                  (Falls back to scanning for the `TEXV0005\\0` magic if the TOC
                  cannot be trusted -- some third-party tools write odd TOCs.)
2. `.tex`       : TEXV0005 + TEXI0001 magics, then 7 x int32 header.
3. image container `TEXB0001..0004`: if the payload is an embedded PNG/JPEG, just
                  write it out; otherwise read the mip(s).
4. mip          : optional LZ4-block decompression.
5. pixels       : decode format 5 = ETC2_RGBA8 (the whole point of this package).
6. write PNG.

Notes
-----
* The `TEXB0004` container normally stores a few extra `param1/param2/json/param3`
  fields before each mip -- BUT only for video (MP4) payloads. For a still image
  (ImageFormat != MP4) the mip header layout is the older `TEXB0003` one:
  `width, height, isLZ4, decompressedSize, compressedSize, bytes`.
* Channel order: the ETC2_RGBA8 decode below is true RGBA. For 5.mpkg this matches
  the shipped `preview.jpg` at per-channel NCC ~= 0.998, so NO R/B swap is needed.
  (Tools that use the `texture2ddecoder` Python package must swap, because that
  package happens to return BGRA -- see HANDOFF.md.)
"""
from __future__ import annotations

import os
import struct
import sys

import lz4_block
import minipng
import pure_etc2

TEX_FORMATS = {
    0: "RGBA8888", 1: "RGB888", 2: "RGB565", 3: "ETC1_RGB8",
    4: "DXT5/BC3", 5: "ETC2_RGBA8", 6: "DXT3/BC2", 7: "DXT1/BC1",
    8: "RG88", 9: "R8", 12: "BC7",
}
FREEIMAGE = {13: "PNG", 2: "JPEG", 25: "GIF", 35: "MP4", -1: "RAW"}


# --------------------------------------------------------------------------- #
# package (.mpkg / .pkg)
# --------------------------------------------------------------------------- #

def parse_pkg(data: bytes):
    """Return list of (path, offset, length, payload) from a PKGM package.

    Layout (from RePKG `PackageReader`):
        magic      = int32 length + that many bytes   (== "PKGM0019")
        entryCount = int32
        entries    = [ int32 pathLen + path, int32 offset, int32 length ]
        data       = starts right after the TOC; entry payload = data[offset : offset+length]
    """
    if len(data) < 16:
        return None
    (mlen,) = struct.unpack_from("<i", data, 0)
    if not (0 < mlen <= 32):
        return None
    magic = data[4:4 + mlen]
    if not magic.startswith(b"PKGM"):
        return None
    p = 4 + mlen
    (count,) = struct.unpack_from("<i", data, p)
    p += 4
    if not (0 <= count <= 100000):
        return None
    entries = []
    for _ in range(count):
        (plen,) = struct.unpack_from("<i", data, p)
        p += 4
        if not (0 <= plen <= 4096):
            return None
        path = data[p:p + plen].decode("utf-8", "replace")
        p += plen
        off, ln = struct.unpack_from("<ii", data, p)
        p += 8
        entries.append((path, off, ln))
    data_start = p
    out = []
    for path, off, ln in entries:
        s = data_start + off
        out.append((path, off, ln, data[s:s + ln]))
    return out


def find_tex(data: bytes):
    """Locate the .tex payload: prefer a package entry, else scan for the magic."""
    pkgs = parse_pkg(data)
    if pkgs:
        for path, off, ln, payload in pkgs:
            if payload[:9] == b"TEXV0005\x00":
                return payload, path, pkgs
    idx = data.find(b"TEXV0005\x00")
    if idx >= 0:
        return data[idx:], None, pkgs
    raise SystemExit("no .tex payload (TEXV0005) found in this file")


# --------------------------------------------------------------------------- #
# .tex
# --------------------------------------------------------------------------- #

def parse_tex(tex: bytes):
    if tex[0:9] != b"TEXV0005\x00":
        raise SystemExit("bad .tex magic1: %r" % tex[0:9])
    if tex[9:18] != b"TEXI0001\x00":
        raise SystemExit("bad .tex magic2: %r" % tex[9:18])
    fmt, flags, tw, th, iw, ih, unk = struct.unpack_from("<7i", tex, 18)
    p = 46
    cmagic = tex[p:p + 9]
    p += 9
    (image_count,) = struct.unpack_from("<i", tex, p)
    p += 4

    image_format = -1
    if cmagic.startswith(b"TEXB0004"):
        image_format = struct.unpack_from("<i", tex, p)[0]
        p += 4
        is_video = struct.unpack_from("<i", tex, p)[0]
        p += 4
        if image_format == -1 and is_video:
            image_format = 35
    elif cmagic.startswith(b"TEXB0003"):
        image_format = struct.unpack_from("<i", tex, p)[0]
        p += 4

    header = dict(fmt=fmt, flags=flags, texture=(tw, th), image=(iw, ih),
                  container=cmagic.rstrip(b"\x00").decode(), image_count=image_count,
                  image_format=image_format)

    # TEXB0004(video) stores extra params before each mip; stills behave like TEXB0003.
    v4_video = cmagic.startswith(b"TEXB0004") and image_format == 35

    mips = []
    (n_mip,) = struct.unpack_from("<i", tex, p)
    p += 4
    for _ in range(n_mip):
        if v4_video:
            p += 8                                # param1(=1), param2(=2)
            p = tex.index(b"\x00", p) + 1         # null-terminated json
            p += 4                                # param3(=1)
        w, h = struct.unpack_from("<2i", tex, p)
        p += 8
        is_lz4 = struct.unpack_from("<i", tex, p)[0]
        p += 4
        decomp = struct.unpack_from("<i", tex, p)[0]
        p += 4
        bcount = struct.unpack_from("<i", tex, p)[0]
        p += 4
        blob = tex[p:p + bcount]
        p += bcount
        raw = lz4_block.decompress(blob, decomp) if is_lz4 else blob
        mips.append((w, h, raw))
    header["mipmap_count"] = n_mip
    return header, mips


# --------------------------------------------------------------------------- #
# pixel decode
# --------------------------------------------------------------------------- #

def decode_pixels(fmt: int, raw: bytes, w: int, h: int, image_format: int):
    """-> (rgb_bytes, channels) or None if we only produced a file (embedded image)."""
    if fmt == 5:                                   # ETC2_RGBA8 (the undocumented one)
        rgba = pure_etc2.decode_etc2_rgba8(raw, w, h)
        # strip alpha for a 3-channel PNG
        rgb = bytearray(w * h * 3)
        j = 0
        for i in range(0, len(rgba), 4):
            rgb[j] = rgba[i]
            rgb[j + 1] = rgba[i + 1]
            rgb[j + 2] = rgba[i + 2]
            j += 3
        return bytes(rgb), 3
    if fmt == 0:                                   # RGBA8888
        n = w * h * 4
        return raw[:n], 4
    if fmt == 9:                                   # R8 -> gray
        return raw[:w * h], 1
    if fmt in (4, 6, 7, 12):                       # DXT1/3/5, BC7
        raise SystemExit(
            "format %d (%s) needs a BC decoder; this reference only implements ETC2_RGBA8 + raw.\n"
            "Use `texture2ddecoder` (see requirements.txt) or port a BC decoder." %
            (fmt, TEX_FORMATS.get(fmt, "?")))
    raise SystemExit("unsupported raw format %d" % fmt)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    src = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.splitext(src)[0] + "_original.png"

    data = open(src, "rb").read()
    print("input:", src, len(data), "bytes")

    tex, path, pkgs = find_tex(data)
    if pkgs:
        print("package entries: %d" % len(pkgs))
        for pth, off, ln, _ in pkgs:
            mark = "  <-- texture" if pth == path else ""
            print("   %-70s off=%-9d len=%d%s" % (pth, off, ln, mark))

    header, mips = parse_tex(tex)
    print("tex: format=%d (%s) flags=%d texture=%s image=%s %s imageFormat=%d(%s)" % (
        header["fmt"], TEX_FORMATS.get(header["fmt"], "?"), header["flags"],
        header["texture"], header["image"], header["container"],
        header["image_format"], FREEIMAGE.get(header["image_format"], "?")))

    # embedded image file (PNG/JPEG/GIF) -> dump bytes verbatim
    if header["image_format"] in (13, 2, 25):
        ext = {13: "png", 2: "jpg", 25: "gif"}[header["image_format"]]
        out = os.path.splitext(out)[0] + "." + ext
        open(out, "wb").write(mips[0][2])
        print("wrote embedded image:", out)
        return out

    if not mips:
        raise SystemExit("no mipmaps")
    w, h, raw = mips[0]
    print("mip 0: %dx%d  raw=%d bytes  (expected 1 B/px for ETC2: %d)" % (w, h, len(raw), w * h))

    rgb, channels = decode_pixels(header["fmt"], raw, w, h, header["image_format"])
    minipng.write_png(out, rgb, w, h, channels)
    print("SAVED:", out, "%dx%d channels=%d" % (w, h, channels))
    return out


if __name__ == "__main__":
    main(sys.argv)
