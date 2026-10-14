# -*- coding: utf-8 -*-
"""
minipng.py -- minimal PNG writer using only the standard library (struct + zlib).
Enough for our purpose: 8-bit RGB / RGBA / grayscale, non-interlaced.
"""
from __future__ import annotations
import struct
import zlib

__all__ = ["write_png"]


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + tag + data +
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def write_png(path, pixels, width: int, height: int, channels: int = 3) -> None:
    """Write a PNG. `pixels` is a bytes-like of width*height*channels, row-major.

    channels: 1 (gray), 3 (RGB), 4 (RGBA).
    """
    if channels not in (1, 3, 4):
        raise ValueError("channels must be 1, 3 or 4")
    stride = width * channels
    expected = stride * height
    if len(pixels) != expected:
        raise ValueError("pixels length %d != %d" % (len(pixels), expected))

    raw = bytearray()
    mv = memoryview(pixels)
    for y in range(height):
        raw.append(0)                       # filter type 0 (None)
        raw += mv[y * stride:(y + 1) * stride]

    color_type = {1: 0, 3: 2, 4: 6}[channels]
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)))
        f.write(_chunk(b"IDAT", zlib.compress(bytes(raw), 6)))
        f.write(_chunk(b"IEND", b""))
