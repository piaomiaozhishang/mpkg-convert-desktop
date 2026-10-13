# -*- coding: utf-8 -*-
"""
lz4_block.py -- pure-Python decompressor for the *LZ4 block* format (no frame header).

Wallpaper Engine stores each `.tex` mip as a raw LZ4 block (no 4-byte magic, no
frame descriptor), with the decompressed size known from the mip header. So a
block-format decompressor is all that is needed -- no `python-lz4` dependency.

Reference: https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md

    sequence  := token literals [offset matchlength]
    token     := (literal_length << 4) | (match_length - 4)     (each nibble max 15)
    extension := if a nibble == 15, additional 0xFF bytes are added (255 each)
                 until a byte < 0xFF terminates the run
    offset    := 2-byte little-endian back-reference distance (1..65535)
    match     := copy `match_length` bytes from `offset` bytes back in the OUTPUT,
                 which may overlap (i.e. act like RLE / periodic extension)
"""
from __future__ import annotations

__all__ = ["decompress"]


def decompress(src: bytes, uncompressed_size: int | None = None) -> bytes:
    """Decompress an LZ4 block.

    If `uncompressed_size` is given it is enforced (a mismatch raises ValueError).
    """
    dst = bytearray()
    n = len(src)
    i = 0
    while i < n:
        token = src[i]
        i += 1

        # ---- literals ----
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]
                i += 1
                lit += b
                if b != 0xFF:
                    break
        if lit:
            dst += src[i:i + lit]
            i += lit
        if i >= n:
            break  # last sequence: literals only

        # ---- match ----
        offset = src[i] | (src[i + 1] << 8)
        i += 2
        if offset == 0:
            raise ValueError("invalid LZ4 offset 0")

        mlen = token & 0x0F
        if mlen == 15:
            while True:
                b = src[i]
                i += 1
                mlen += b
                if b != 0xFF:
                    break
        mlen += 4

        start = len(dst) - offset
        if start < 0:
            raise ValueError("LZ4 offset %d beyond output (%d)" % (offset, len(dst)))
        if offset >= mlen:
            # no overlap -> single slice copy
            dst += dst[start:start + mlen]
        else:
            # overlapping -> replicate the `offset`-byte period
            period = bytes(dst[start:])            # len == offset
            reps, rem = divmod(mlen, offset)
            dst += period * reps
            dst += period[:rem]

    out = bytes(dst)
    if uncompressed_size is not None and len(out) != uncompressed_size:
        raise ValueError("LZ4 size mismatch: got %d, expected %d" % (len(out), uncompressed_size))
    return out
